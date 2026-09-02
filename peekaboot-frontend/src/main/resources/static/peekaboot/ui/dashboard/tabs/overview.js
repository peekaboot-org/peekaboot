/**
 * The "Overview" tab: the insights stat-tile row, build/git/Spring/Java/OS/machine/JVM
 * info + datasource cards, the memory/storage usage meters and the health banner +
 * component grid.
 */
import {kvRow, badge, meter} from '../../shared/components.js';
import {escapeHtml} from '../../shared/markup.js';
import {healthSeverity} from '../../shared/severity.js';
import {formatBytes, formatDateTime, formatHosts, formatTileValue} from '../../shared/format.js';

export const id = 'overview';
export const label = 'Overview';

export function render(container, data, context = {}) {
    const {locale, timeZone} = context;
    const {application, runtime, dataSources, health} = data;

    renderInsightTiles(container, context);
    renderBuildInfo(container, application?.build, {locale, timeZone});
    renderGitInfo(container, application?.git, {locale, timeZone});
    renderSpringInfo(container, application);
    renderJavaInfo(container, application);
    renderOsInfo(container, runtime);
    renderMachineInfo(container, runtime);
    renderJvmDefaults(container, data.server, {locale, timeZone});
    renderDataSourcesInfo(container, dataSources);
    renderMemoryInfo(container, runtime);
    renderHealthBanner(container, health);
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
 * Fills the tile row from /api/insights/config, whose tiles carry their current value
 * alongside their definition - so the dashboard's own 30s cycle keeps them current and
 * this tab needs none of the Insights tab's SSE machinery. The row is hidden outright,
 * rather than left as an empty box, whenever insights are switched off or unreachable.
 */
async function renderInsightTiles(container, {client, features, locale, timeZone} = {}) {
    const row = container.querySelector('#insights-tiles');
    if (!row) return;
    if (!features?.insights || !client) {
        row.classList.add('hidden');
        return;
    }

    let config;
    try {
        // own dedupe key: the Insights tab loads this same path on its own schedule,
        // and on a "#insights" deep link both fire in the same cycle - sharing the
        // default per-path counter left whichever called first with a null and this
        // row hidden until the next refresh (see shared/api.js)
        config = await client.get('/api/insights/config', {dedupeKey: 'insight-tiles'});
    } catch (error) {
        console.warn('Insight tiles unavailable:', error);
        row.classList.add('hidden');
        return;
    }
    // null now only means this row's own previous call is still in flight and a newer
    // one has taken over - that newer response is about to render the very same row
    if (!config) return;

    row.innerHTML = config.tiles.map(tile => `
        <div class="pk-insight-tile" data-tile-id="${escapeHtml(tile.id)}">
            ${tileIcon(tile.id)}
            <div class="pk-insight-tile__text">
                <div class="pk-insight-tile-label">${escapeHtml(tile.label)}</div>
                <div class="pk-insight-tile-value">${escapeHtml(formatTileValue(tile.value, tile.format, {locale, timeZone}))}</div>
            </div>
        </div>
    `).join('');
    row.classList.toggle('hidden', config.tiles.length === 0);
}

function renderBuildInfo(container, build, dateOptions) {
    const el = container.querySelector('#build-info');
    el.innerHTML = '';

    if (!build || Object.keys(build).length === 0) {
        el.innerHTML = '<p class="pk-empty">No build info available</p>';
        return;
    }

    if (build.name) el.appendChild(kvRow('Name', build.name));
    if (build.version) el.appendChild(kvRow('Version', build.version));
    if (build.group) el.appendChild(kvRow('Group', build.group));
    if (build.artifact) el.appendChild(kvRow('Artifact', build.artifact));
    if (build.time) el.appendChild(kvRow('Built', formatDateTime(build.time, dateOptions)));
}

function renderGitInfo(container, git, dateOptions) {
    const el = container.querySelector('#git-info');
    el.innerHTML = '';

    if (!git || Object.keys(git).length === 0) {
        el.innerHTML = '<p class="pk-empty">No git info available</p>';
        return;
    }

    if (git.branch) el.appendChild(kvRow('Branch', git.branch));
    if (git.commit?.id) {
        const commitId = git.commit.id.abbrev || git.commit.id;
        el.appendChild(kvRow('Commit', commitId, {mono: true}));
    }
    if (git.commit?.time) el.appendChild(kvRow('Commit Time', formatDateTime(git.commit.time, dateOptions)));
    if (git.dirty !== undefined) el.appendChild(kvRow('Dirty', git.dirty ? 'Yes' : 'No'));
}

function renderSpringInfo(container, application) {
    const el = container.querySelector('#spring-info');
    if (!el) return;
    el.innerHTML = '';

    if (!application) {
        el.innerHTML = '<p class="pk-empty">No Spring info available</p>';
        return;
    }

    if (application.springBootVersion) el.appendChild(kvRow('Boot', application.springBootVersion));
    if (application.springFrameworkVersion) el.appendChild(kvRow('Framework', application.springFrameworkVersion));
}

function renderJavaInfo(container, application) {
    const el = container.querySelector('#java-info');
    el.innerHTML = '';

    if (!application) {
        el.innerHTML = '<p class="pk-empty">No Java info available</p>';
        return;
    }

    if (application.javaVersion) el.appendChild(kvRow('Version', application.javaVersion));
    if (application.javaVendor) el.appendChild(kvRow('Vendor', application.javaVendor));
}

function renderOsInfo(container, runtime) {
    const el = container.querySelector('#os-info');
    el.innerHTML = '';

    const os = runtime?.os;
    const process = runtime?.process;

    if (!os && !process) {
        el.innerHTML = '<p class="pk-empty">No system info available</p>';
        return;
    }

    if (os) {
        if (os.name) el.appendChild(kvRow('OS', `${os.name} ${os.version || ''}`));
        if (os.arch) el.appendChild(kvRow('Architecture', os.arch));
    }

    if (process) {
        let userDisplay = process.username || '';
        if (process.uid != null) userDisplay += ` (uid=${process.uid}, gid=${process.gid})`;
        el.appendChild(kvRow('User', userDisplay));
        el.appendChild(kvRow('PID', process.pid, {mono: true}));

        if (process.parentProcesses && process.parentProcesses.length > 0) {
            const tree = process.parentProcesses
                .map(p => p.command ? `${p.command}(${p.pid})` : String(p.pid))
                .reverse()
                .join(' → ');
            el.appendChild(kvRow('Process Tree', tree, {mono: true}));
        }
    }
}

/**
 * The deployment environment: CPU, physical memory, the JVM's max heap, the
 * container runtime the backend detected ("none" outside one) and the machine's
 * non-local IP addresses. cpuCount and totalMemory come from the JDK, which is
 * container-aware - inside a limited container they report the container's share,
 * not the host's. Every other fact is best-effort: what the backend couldn't read
 * arrives null/empty and renders no row at all.
 */
function renderMachineInfo(container, runtime) {
    const el = container.querySelector('#machine-info');
    el.innerHTML = '';

    const machine = runtime?.machine;
    if (!machine) {
        el.innerHTML = '<p class="pk-empty">No machine info available</p>';
        return;
    }

    if (machine.cpuCount) el.appendChild(kvRow('CPU Cores', cpuCoresValue(machine)));
    if (machine.cpuModel) el.appendChild(kvRow('CPU Model', machine.cpuModel));
    if (machine.totalMemory) el.appendChild(kvRow('Total Memory', formatBytes(machine.totalMemory)));
    if (machine.maxHeap) el.appendChild(kvRow('Max Heap', formatBytes(machine.maxHeap)));
    el.appendChild(kvRow('Container', machine.container || 'none'));

    (machine.networkAddresses ?? []).forEach(({address, hostname}) => {
        el.appendChild(kvRow('IP Address', hostname ? `${address} (${hostname})` : address, {mono: true}));
    });
}

/**
 * The logical count, annotated with the physical layout where /proc/cpuinfo gave the
 * backend one - "8 (4 cores × 2 threads)" with SMT/hyper-threading active. The plain
 * count means the topology is unknown (non-Linux, exotic kernels).
 */
function cpuCoresValue(machine) {
    const topology = machine.cpuTopology;
    if (!topology) return machine.cpuCount;
    return topology.threadsPerCore > 1
        ? `${machine.cpuCount} (${topology.physicalCores} cores × ${topology.threadsPerCore} threads)`
        : `${machine.cpuCount} (${topology.physicalCores} cores, SMT off)`;
}

function renderJvmDefaults(container, server, {locale, timeZone}) {
    const el = container.querySelector('#jvm-defaults-info');
    el.innerHTML = '';

    if (!server) {
        el.innerHTML = '<p class="pk-empty">No JVM defaults available</p>';
        return;
    }

    if (server.locale) {
        const localeDisplay = server.localeDisplay ? ` (${server.localeDisplay})` : '';
        el.appendChild(kvRow('Locale', `${server.locale}${localeDisplay}`));
    }
    if (server.timezone) {
        const tzDisplay = server.timezoneOffset ? ` (UTC${server.timezoneOffset})` : '';
        el.appendChild(kvRow('Timezone', `${server.timezone}${tzDisplay}`));
    }
    if (server.currentTime) {
        el.appendChild(kvRow('Server Time',
            formatDateTime(server.currentTime, {locale, timeZone, dateStyle: 'medium', timeStyle: 'medium'})));
    }
    if (server.fileEncoding) el.appendChild(kvRow('File Encoding', server.fileEncoding));
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
    const card = document.createElement('div');
    card.className = 'pk-card';
    card.dataset.datasource = ds.name || 'DataSource';

    const header = document.createElement('div');
    header.className = 'pk-card__header';
    header.innerHTML = `<span class="pk-card__icon" aria-hidden="true">\u{1F5C2}</span>`
            + `<h2 class="pk-card__title">${escapeHtml(ds.name || 'DataSource')}</h2>`;
    if (ds.health) header.appendChild(badge(ds.health, healthSeverity(ds.health)));

    const body = document.createElement('div');
    body.className = 'pk-card__body';
    const dbProduct = ds.databaseProduct ? (ds.databaseProduct.displayName || ds.databaseProduct) : '-';
    body.appendChild(kvRow('Database', ds.databaseName));
    body.appendChild(kvRow('Host', formatHosts(ds.hosts), {mono: true}));
    body.appendChild(kvRow('User', ds.username));
    body.appendChild(kvRow('Product', String(dbProduct)));
    body.appendChild(kvRow('Driver', ds.driver));

    card.append(header, body);

    const params = Object.entries(ds.properties || {});
    if (params.length > 0) {
        const paramsEl = document.createElement('div');
        paramsEl.className = 'pk-datasources__params';
        paramsEl.hidden = true;

        const list = document.createElement('div');
        list.className = 'pk-datasources__params-list';
        // The backend (MaskingEngine) already masked sensitive connection params -
        // this just renders whatever value it sent, same as every other tab.
        params.forEach(([key, value]) => {
            list.appendChild(kvRow(key, value, {tight: true, mono: true}));
        });
        paramsEl.appendChild(list);

        const toggle = document.createElement('div');
        toggle.className = 'pk-datasources__toggle';
        const toggleBtn = document.createElement('button');
        toggleBtn.type = 'button';
        toggleBtn.className = 'pk-btn pk-btn--small';
        toggleBtn.textContent = 'Show Connection Params';
        toggle.appendChild(toggleBtn);
        body.appendChild(toggle);
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
    const wrapper = document.createElement('div');
    wrapper.className = 'pk-section';
    wrapper.appendChild(kvRow(label, caption, {tight: true}));
    wrapper.appendChild(meter(percent));
    return wrapper;
}

function renderMemoryInfo(container, runtime) {
    const el = container.querySelector('#memory-info');
    const processInfo = container.querySelector('#process-info');
    el.innerHTML = '';

    const memory = runtime?.memory;
    const storage = runtime?.storage;

    if (!memory && (!storage || storage.length === 0)) {
        el.innerHTML = '<p class="pk-empty">No memory info available</p>';
        processInfo.textContent = '';
        return;
    }

    processInfo.textContent = '';

    if (memory) {
        const hasMax = memory.heapMax && memory.heapMax > 0;
        const heapPercent = memory.heapUsedPercent ?? (hasMax ? (memory.heapUsed / memory.heapMax) * 100 : 0);
        el.appendChild(usageSection('Heap',
            `${formatBytes(memory.heapUsed)} / ${formatBytes(memory.heapMax)} (${heapPercent.toFixed(1)}%)`,
            heapPercent));

        if (memory.nonHeapUsed) {
            el.appendChild(kvRow('Non-Heap', formatBytes(memory.nonHeapUsed), {tight: true}));
        }
    }

    if (storage && storage.length > 0) {
        storage.forEach(s => {
            const used = s.total - s.free;
            const percent = s.usedPercent ?? (s.total > 0 ? (used / s.total) * 100 : 0);
            el.appendChild(usageSection(s.path || 'Disk',
                `${formatBytes(used)} used / ${formatBytes(s.total)} total (${formatBytes(s.free)} free)`,
                percent));
        });
    }
}

/** 'error' -> 'down', 'muted' -> 'unknown', 'ok' -> no modifier. */
function healthModifier(severity) {
    if (severity === 'error') return 'down';
    if (severity === 'muted') return 'unknown';
    return null;
}

function renderHealthBanner(container, health) {
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

    // classList, not className - a full reassignment would also wipe out the layout
    // classes (pk-grid--full pk-section) the static markup put on #health-banner.
    dot.classList.remove('pk-health__dot--down', 'pk-health__dot--unknown');
    banner.classList.remove('pk-health--down', 'pk-health--unknown');
    if (modifier) {
        dot.classList.add(`pk-health__dot--${modifier}`);
        banner.classList.add(`pk-health--${modifier}`);
    }

    const components = health?.components;
    summary.textContent = components && components.length > 0
        ? `${components.filter(c => c.status === 'UP').length}/${components.length} healthy`
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
        grid.innerHTML = '<p class="pk-empty">No health components available</p>';
        return;
    }

    components.forEach(component => {
        const card = document.createElement('div');
        card.className = 'pk-component-grid__card';

        const modifier = healthModifier(healthSeverity(component.status || 'UNKNOWN'));
        const dotClass = 'pk-component-grid__dot' + (modifier ? ` pk-component-grid__dot--${modifier}` : '');

        let detailsHtml = '';
        if (component.details && Object.keys(component.details).length > 0) {
            const detailsText = Object.entries(component.details)
                .filter(([, v]) => v !== null && v !== '' && (!Array.isArray(v) || v.length > 0))
                .map(([k, v]) => {
                    if (k === 'total' || k === 'free' || k === 'threshold') return `${k}: ${formatBytes(v)}`;
                    return `${k}: ${formatDetailValue(v)}`;
                })
                .join('\n');
            if (detailsText) detailsHtml = `<div class="pk-component-grid__details">${escapeHtml(detailsText)}</div>`;
        }

        card.innerHTML = `
            <div class="pk-component-grid__header">
                <span class="pk-component-grid__name">${escapeHtml(component.name)}</span>
                <span class="${dotClass}"></span>
            </div>
            ${detailsHtml}
        `;

        grid.appendChild(card);
    });
}

function formatDetailValue(value) {
    if (typeof value === 'boolean') return value ? 'Yes' : 'No';
    if (Array.isArray(value)) return value.length > 0 ? value.join(', ') : '-';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
}
