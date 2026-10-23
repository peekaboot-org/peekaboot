/**
 * The "Overview" tab: the insights stat-tile row, build/git/Spring/Java/OS/machine/JVM
 * info + datasource cards, the memory/storage usage meters and the health banner +
 * component grid.
 */
import {kvRow, badge, meter, tabStrip, emptyState} from '../../shared/components.js';
import {el, button} from '../../shared/dom.js';
import {escapeHtml} from '../../shared/markup.js';
import {healthSeverity} from '../../shared/severity.js';
import {formatBytes, formatDateTime, formatDateTimeWith, formatHosts, formatNumber, formatPlainValue, formatTileValue} from '../../shared/format.js';
import {selfFetchingTab} from '../../shared/self-fetching-tab.js';

export const id = 'overview';

export function render(container, data, context = {}) {
    const {locale, timeZone} = context;
    const {application, runtime, dataSources, health} = data;

    renderInsightTiles(container, data, context);
    renderBuildInfo(container, application?.build, {locale, timeZone});
    renderGitInfo(container, application?.git, {locale, timeZone});
    renderSpringInfo(container, application);
    renderJavaInfo(container, application);
    renderOsInfo(container, runtime);
    renderMachineInfo(container, runtime, locale);
    renderJvmDefaults(container, data.server, {locale, timeZone});
    renderDataSourcesInfo(container, dataSources);
    renderMemoryInfo(container, runtime);
    renderHealthBanner(container, health, locale);
    renderHealthComponents(container, health?.components);
}

/**
 * The insights stat tiles (started at / startup / ready / uptime). They are
 * config-driven server-side, so the icons are keyed by tile id with a neutral fallback
 * for any tile an application adds in its own peekaboot-insights.yml. 24x24 viewBox,
 * drawn in the surrounding text colour so they follow the theme with no extra tokens.
 */
const TILE_ICONS = new Map([
    ['started-at', '<rect x="3" y="4" width="18" height="17" rx="2"/><path d="M8 2v4M16 2v4M3 10h18"/>'],
    ['startup-time', '<path d="M13 2 4 14h7l-1 8 9-12h-7l1-8z"/>'],
    ['ready-time', '<circle cx="12" cy="12" r="9"/><path d="m8.5 12 2.5 2.5 4.5-5"/>'],
    ['uptime', '<path d="M10 2h4M12 14v-4"/><circle cx="12" cy="14" r="8"/>']
]);
const FALLBACK_TILE_ICON = '<path d="M3 12h4l3 8 4-16 3 8h4"/>';

function tileIcon(tileId) {
    return `<svg class="pk-insight-tile__icon" viewBox="0 0 24 24" width="18" height="18" fill="none"
            stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"
            aria-hidden="true">${TILE_ICONS.get(tileId) ?? FALLBACK_TILE_ICON}</svg>`;
}

/**
 * The tile row's own fetch of /api/insights/config, whose tiles carry their current value
 * alongside their definition - so the dashboard's own 30s cycle keeps them current and
 * this tab needs none of the Insights tab's SSE machinery. On the self-fetching-tab
 * contract: fetched only while this tab is the one showing, and hidden outright, rather
 * than left as an empty box, when the endpoint is unreachable.
 */
const tileRow = selfFetchingTab({
    // own dedupe key: the Insights tab loads this same path on its own schedule, and on
    // a "#insights" deep link both fire in the same cycle - sharing the default per-path
    // counter would leave whichever called first with a null and this row hidden until
    // the next refresh (see shared/api.js)
    fetch: ({client}) => client.get('/api/insights/config', {dedupeKey: 'insight-tiles'}),
    renderResult: (container, config, {locale, timeZone}) => {
        const tiles = config.tiles ?? [];
        const row = container.querySelector('#insights-tiles');
        row.innerHTML = tiles.map(tile => `
            <div class="pk-insight-tile" data-tile-id="${escapeHtml(tile.id)}">
                ${tileIcon(tile.id)}
                <div class="pk-insight-tile__text">
                    <div class="pk-insight-tile__label pk-label">${escapeHtml(tile.label)}</div>
                    <div class="pk-insight-tile__value">${escapeHtml(formatTileValue(tile.value, tile.format, {locale, timeZone}))}</div>
                </div>
            </div>
        `).join('');
        row.classList.toggle('hidden', tiles.length === 0);
    },
    renderError: (container, error) => {
        console.warn('Insight tiles unavailable:', error);
        container.querySelector('#insights-tiles').classList.add('hidden');
    }
});

/** The row is hidden outright when insights are switched off; otherwise the tile fetch above fills it. */
function renderInsightTiles(container, data, context) {
    const row = container.querySelector('#insights-tiles');
    if (!row) return;
    if (!context.features?.insights || !context.client) {
        row.classList.add('hidden');
        return;
    }
    tileRow.render(container, data, context);
}

/**
 * Fills a card body from `value`, or shows `emptyMessage` when there is nothing to show
 * (null, or an object with no entries).
 */
function fillCard(container, selector, value, emptyMessage, fill) {
    const body = container.querySelector(selector);
    if (!body) return;
    body.innerHTML = '';
    if (!value || Object.keys(value).length === 0) {
        body.appendChild(emptyState(emptyMessage));
        return;
    }
    fill(body, value);
}

function renderBuildInfo(container, build, dateOptions) {
    fillCard(container, '#build-info', build, 'No build info available', body => {
        if (build.name) body.appendChild(kvRow('Name', build.name));
        if (build.version) body.appendChild(kvRow('Version', build.version));
        if (build.group) body.appendChild(kvRow('Group', build.group));
        if (build.artifact) body.appendChild(kvRow('Artifact', build.artifact));
        if (build.time) body.appendChild(kvRow('Built', formatDateTime(build.time, dateOptions)));
    });
}

function renderGitInfo(container, git, dateOptions) {
    fillCard(container, '#git-info', git, 'No git info available', body => {
        if (git.branch) body.appendChild(kvRow('Branch', git.branch));
        if (git.commit?.id) {
            const commitId = git.commit.id.abbrev || git.commit.id;
            body.appendChild(kvRow('Commit', commitId, {mono: true}));
        }
        if (git.commit?.time) body.appendChild(kvRow('Commit Time', formatDateTime(git.commit.time, dateOptions)));
        if (git.dirty !== undefined) body.appendChild(kvRow('Dirty', git.dirty ? 'Yes' : 'No'));
    });
}

function renderSpringInfo(container, application) {
    fillCard(container, '#spring-info', application, 'No Spring info available', body => {
        if (application.springBootVersion) body.appendChild(kvRow('Boot', application.springBootVersion));
        if (application.springFrameworkVersion) body.appendChild(kvRow('Framework', application.springFrameworkVersion));
    });
}

function renderJavaInfo(container, application) {
    fillCard(container, '#java-info', application, 'No Java info available', body => {
        if (application.javaVersion) body.appendChild(kvRow('Version', application.javaVersion));
        if (application.javaVendor) body.appendChild(kvRow('Vendor', application.javaVendor));
    });
}

function renderOsInfo(container, runtime) {
    const os = runtime?.os;
    const process = runtime?.process;

    fillCard(container, '#os-info', os || process ? {os, process} : null, 'No system info available', body => {
        if (os) {
            if (os.name) body.appendChild(kvRow('OS', `${os.name} ${os.version || ''}`));
            if (os.arch) body.appendChild(kvRow('Architecture', os.arch));
        }

        if (process) {
            let userDisplay = process.username || '';
            if (process.uid != null) userDisplay += ` (uid=${process.uid}, gid=${process.gid})`;
            body.appendChild(kvRow('User', userDisplay));
            body.appendChild(kvRow('PID', process.pid, {mono: true}));

            if (process.parentProcesses && process.parentProcesses.length > 0) {
                const tree = process.parentProcesses
                    .map(p => p.command ? `${p.command}(${p.pid})` : String(p.pid))
                    .reverse()
                    .join(' → ');
                body.appendChild(kvRow('Process Tree', tree, {mono: true}));
            }
        }
    });
}

/**
 * The deployment environment: CPU, physical memory, the JVM's max heap, the
 * container runtime the backend detected ("none" outside one) and the machine's
 * non-local IP addresses. cpuCount and totalMemory come from the JDK, which is
 * container-aware - inside a limited container they report the container's share,
 * not the host's. Every other fact is best-effort: what the backend couldn't read
 * arrives null/empty and renders no row at all.
 */
function renderMachineInfo(container, runtime, locale) {
    const body = container.querySelector('#machine-info');
    const previousFamily = body.querySelector('#machine-net-tabs .pk-tab[aria-selected="true"]')?.dataset.tab;
    body.innerHTML = '';

    const machine = runtime?.machine;
    if (!machine) {
        body.appendChild(emptyState('No machine info available'));
        return;
    }

    if (machine.cpuCount) body.appendChild(kvRow('CPU Cores', cpuCoresValue(machine, locale)));
    if (machine.cpuModel) body.appendChild(kvRow('CPU Model', machine.cpuModel));
    if (machine.totalMemory) body.appendChild(kvRow('Total Memory', formatBytes(machine.totalMemory)));
    if (machine.maxHeap) body.appendChild(kvRow('Max Heap', formatBytes(machine.maxHeap)));
    body.appendChild(kvRow('Container', machine.container || 'none'));

    renderNetworkAddresses(body, machine.networkAddresses ?? [], previousFamily);
}

const IP_FAMILIES = [
    {id: 'ipv4', label: 'IPv4'},
    {id: 'ipv6', label: 'IPv6'}
];

/** A ":" in the literal means IPv6 - dotted-quad IPv4 never carries one. */
function ipFamily(address) {
    return address.includes(':') ? 'ipv6' : 'ipv4';
}

/**
 * The machine's non-local IP addresses, split into an IPv4/IPv6 tab strip. The tab
 * labels carry the context a key column would, so the rows are bare full-width
 * address (+ hostname) text. A family with no addresses hides its tab, the same way
 * the main strip hides tabs for absent features; with no addresses at all nothing
 * renders. Which family is open is transient per-browser UI - carried across the
 * refresh rebuild via previousFamily (the group-expansion convention), never into
 * the URL.
 */
function renderNetworkAddresses(body, addresses, previousFamily) {
    if (addresses.length === 0) return;

    const strip = el('div', {className: 'pk-tabs', attrs: {id: 'machine-net-tabs'}});
    body.appendChild(strip);

    const panels = new Map();
    IP_FAMILIES.forEach(({id, label}) => {
        const familyAddresses = addresses.filter(({address}) => ipFamily(address) === id);
        const buttonId = `machine-net-${id}-btn`;

        strip.appendChild(button({
            className: 'pk-tab' + (familyAddresses.length === 0 ? ' hidden' : ''),
            text: label,
            attrs: {id: buttonId, 'data-tab': id, role: 'tab', 'aria-controls': `machine-net-${id}`}
        }));

        const panel = el('div', {attrs: {id: `machine-net-${id}`, role: 'tabpanel', 'aria-labelledby': buttonId}},
            ...familyAddresses.map(({address, hostname}) =>
                el('div', {className: 'pk-machine-net__addr', text: hostname ? `${address} (${hostname})` : address})));
        body.appendChild(panel);
        if (familyAddresses.length > 0) panels.set(id, panel);
    });

    const showFamily = id => panels.forEach((panel, family) => panel.hidden = family !== id);
    const initial = panels.has(previousFamily) ? previousFamily : panels.keys().next().value;
    tabStrip(strip, IP_FAMILIES, {onSelect: showFamily, initial});
    showFamily(initial);
}

/**
 * The logical count, annotated with the physical layout where /proc/cpuinfo gave the
 * backend one - "8 (4 cores × 2 threads)" with SMT/hyper-threading active. The plain
 * count means the topology is unknown (non-Linux, exotic kernels).
 */
function cpuCoresValue(machine, locale) {
    const topology = machine.cpuTopology;
    const cpuCount = formatNumber(machine.cpuCount, {locale});
    if (!topology) return cpuCount;
    return topology.threadsPerCore > 1
        ? `${cpuCount} (${formatNumber(topology.physicalCores, {locale})} cores × ${formatNumber(topology.threadsPerCore, {locale})} threads)`
        : `${cpuCount} (${formatNumber(topology.physicalCores, {locale})} cores, SMT off)`;
}

function renderJvmDefaults(container, server, {locale, timeZone}) {
    fillCard(container, '#jvm-defaults-info', server, 'No JVM defaults available', body => {
        if (server.locale) {
            const localeDisplay = server.localeDisplay ? ` (${server.localeDisplay})` : '';
            body.appendChild(kvRow('Locale', `${server.locale}${localeDisplay}`));
        }
        if (server.timezone) {
            const tzDisplay = server.timezoneOffset ? ` (UTC${server.timezoneOffset})` : '';
            body.appendChild(kvRow('Timezone', `${server.timezone}${tzDisplay}`));
        }
        if (server.currentTime) {
            body.appendChild(kvRow('Server Time',
                formatDateTimeWith(server.currentTime, {dateStyle: 'medium', timeStyle: 'medium'}, {locale, timeZone})));
        }
        if (server.fileEncoding) body.appendChild(kvRow('File Encoding', server.fileEncoding));
    });
}

/**
 * Datasource cards join the main card grid right after JVM Defaults, so a single
 * datasource sits beside it in the two-column layout and further ones flow on in
 * grid order. Rebuilt from scratch on every refresh cycle.
 */
function renderDataSourcesInfo(container, dataSources) {
    container.querySelectorAll('.pk-card[data-datasource]').forEach(card => card.remove());
    container.querySelector('#jvm-defaults-card').after(...(dataSources ?? []).map(renderDataSourceCard));
}

function renderDataSourceCard(ds) {
    const name = ds.name || 'DataSource';
    const header = el('div', {className: 'pk-card__header pk-label'},
        el('span', {className: 'pk-card__icon', text: '\u{1F5C2}', attrs: {'aria-hidden': 'true'}}),
        el('h2', {className: 'pk-card__title', text: name}),
        ds.health ? badge(ds.health, healthSeverity(ds.health)) : null);

    const dbProduct = ds.databaseProduct ? (ds.databaseProduct.displayName || ds.databaseProduct) : '-';
    const body = el('div', {className: 'pk-card__body'},
        kvRow('Database', ds.databaseName),
        kvRow('Host', formatHosts(ds.hosts), {mono: true}),
        kvRow('User', ds.username),
        kvRow('Product', String(dbProduct)),
        kvRow('Driver', ds.driver));

    const card = el('div', {className: 'pk-card', attrs: {'data-datasource': name}}, header, body);

    const params = Object.entries(ds.properties || {});
    if (params.length > 0) {
        // The backend (MaskingEngine) already masked sensitive connection params -
        // this just renders whatever value it sent, same as every other tab.
        const paramsEl = el('div', {className: 'pk-datasources__params', attrs: {hidden: ''}},
            el('div', {className: 'pk-datasources__params-list'},
                ...params.map(([key, value]) => kvRow(key, value, {tight: true, mono: true}))));

        const toggleBtn = button({className: 'pk-btn pk-btn--small', text: 'Show Connection Params'});
        body.appendChild(el('div', {className: 'pk-datasources__toggle'}, toggleBtn));
        card.appendChild(paramsEl);

        toggleBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            paramsEl.hidden = !paramsEl.hidden;
            toggleBtn.textContent = paramsEl.hidden ? 'Show Connection Params' : 'Hide Connection Params';
        });
    }

    return card;
}

/** A usage meter with its caption row. */
function usageSection(label, caption, percent) {
    return el('div', {className: 'pk-section'}, kvRow(label, caption, {tight: true}), meter(percent));
}

function renderMemoryInfo(container, runtime) {
    const body = container.querySelector('#memory-info');
    body.innerHTML = '';

    const memory = runtime?.memory;
    const storage = runtime?.storage;

    if (!memory && (!storage || storage.length === 0)) {
        body.appendChild(emptyState('No memory info available'));
        return;
    }

    if (memory) {
        const heapPercent = memory.heapUsedPercent;
        body.appendChild(usageSection('Heap',
            `${formatBytes(memory.heapUsed)} / ${formatBytes(memory.heapMax)} (${heapPercent.toFixed(1)}%)`,
            heapPercent));

        if (memory.nonHeapUsed) {
            body.appendChild(kvRow('Non-Heap', formatBytes(memory.nonHeapUsed), {tight: true}));
        }
    }

    if (storage && storage.length > 0) {
        storage.forEach(s => {
            const used = s.total - s.free;
            body.appendChild(usageSection(s.path || 'Disk',
                `${formatBytes(used)} used / ${formatBytes(s.total)} total (${formatBytes(s.free)} free)`,
                s.usedPercent));
        });
    }
}

/** 'error' -> 'down', 'muted' -> 'unknown', 'ok' -> no modifier. */
function healthModifier(severity) {
    if (severity === 'error') return 'down';
    if (severity === 'muted') return 'unknown';
    return null;
}

function renderHealthBanner(container, health, locale) {
    const banner = container.querySelector('#health-banner');
    const dot = container.querySelector('#health-dot');
    const statusContainer = container.querySelector('#health-status-container');
    const summary = container.querySelector('#health-summary');
    const healthComponents = container.querySelector('#health-components');

    const status = health?.status || 'UNKNOWN';
    const severity = healthSeverity(status);
    const modifier = healthModifier(severity);

    statusContainer.innerHTML = '';
    const pill = badge(status, severity);
    pill.id = 'health-status-text';
    statusContainer.appendChild(pill);

    // classList, not className - a full reassignment would also wipe out the base
    // classes (pk-health, pk-health__dot) the static markup put on both elements.
    dot.classList.remove('pk-health__dot--down', 'pk-health__dot--unknown');
    banner.classList.remove('pk-health--down', 'pk-health--unknown');
    if (modifier) {
        dot.classList.add(`pk-health__dot--${modifier}`);
        banner.classList.add(`pk-health--${modifier}`);
    }

    const components = health?.components;
    summary.textContent = components && components.length > 0
        ? `${formatNumber(components.filter(c => c.status === 'UP').length, {locale})}/${formatNumber(components.length, {locale})} healthy`
        : '';

    banner.onclick = () => {
        const willExpand = banner.getAttribute('aria-expanded') !== 'true';
        banner.setAttribute('aria-expanded', String(willExpand));
        healthComponents.hidden = !willExpand;
    };
}

function renderHealthComponents(container, components) {
    const grid = container.querySelector('#components-grid');
    grid.innerHTML = '';

    if (!components || components.length === 0) {
        grid.appendChild(emptyState('No health components available'));
        return;
    }

    components.forEach(component => {
        const modifier = healthModifier(healthSeverity(component.status || 'UNKNOWN'));
        const dotClass = 'pk-component-grid__dot' + (modifier ? ` pk-component-grid__dot--${modifier}` : '');
        const detailsText = Object.entries(component.details || {})
            .filter(([, v]) => v !== null && v !== '' && (!Array.isArray(v) || v.length > 0))
            .map(([k, v]) => {
                if (k === 'total' || k === 'free' || k === 'threshold') return `${k}: ${formatBytes(v)}`;
                return `${k}: ${formatDetailValue(v)}`;
            })
            .join('\n');

        grid.appendChild(el('div', {className: 'pk-component-grid__card'},
            el('div', {className: 'pk-component-grid__header'},
                el('span', {className: 'pk-component-grid__name', text: component.name}),
                el('span', {className: dotClass})),
            detailsText ? el('div', {className: 'pk-component-grid__details', text: detailsText}) : null));
    });
}

function formatDetailValue(value) {
    if (typeof value === 'boolean') return value ? 'Yes' : 'No';
    if (Array.isArray(value)) return value.length > 0 ? value.join(', ') : '-';
    return formatPlainValue(value);
}
