/**
 * The "Traces" tab: recent request/job/message traces, bucketed (all/errors/slow) and
 * filterable by root action type, each opening the shared trace-detail overlay when
 * clicked. Owns its own bucket control, type filter and list - nothing here is shared
 * with or driven by another tab module.
 *
 * Fetched from its own endpoint (not part of the main dashboard payload), so it manages
 * its own fetch state independently of the other tabs; context.client's per-path
 * generation counter is the guard against a slow older response overwriting a newer one.
 */
import {badge} from '../../shared/components.js';
import {escapeHtml} from '../../shared/markup.js';
import {formatDurationMs, formatDateTime} from '../../shared/format.js';
import {durationSeverity} from '../../shared/severity.js';
import {ROOT_ACTION_TYPES, rootActionIcon, rootActionLabel} from '../../shared/root-actions.js';

export const id = 'traces';
export const label = 'Traces';

// Empty set means "no type filter" - all traces are shown.
let selectedRootActionTypes = new Set();
let currentBucket = 'all';

const BUCKET_EMPTY_MESSAGES = {
    all: 'No traces recorded',
    errors: 'No error traces recorded',
    slow: 'No slow traces recorded'
};

export function isAvailable(data, features) {
    return Boolean(features?.tracing);
}

export function render(container, data, context) {
    wireControls(container, context);
    fetchAndRender(container, context);
}

function wireControls(container, context) {
    if (container.dataset.wired) return;
    container.dataset.wired = 'true';

    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
        btn.setAttribute('aria-pressed', String(btn.dataset.bucket === currentBucket));
        btn.addEventListener('click', () => {
            if (btn.dataset.bucket === currentBucket) return;
            currentBucket = btn.dataset.bucket;
            container.querySelectorAll('#traces-bucket .pk-btn').forEach(b =>
                b.setAttribute('aria-pressed', String(b === btn)));
            fetchAndRender(container, context);
        });
    });

    renderTypeFilterCheckboxes(container, context);

    const clearBtn = container.querySelector('#traces-filter-clear');
    if (clearBtn) clearBtn.addEventListener('click', () => resetFilter(container, context));
}

/** Generated from ROOT_ACTION_TYPES rather than hardcoded in index.html, so adding a
    root action type no longer means editing HTML. Each checkbox's accessible name comes
    from the wrapping <label>, matching the loggers tab's checkbox-label convention. */
function renderTypeFilterCheckboxes(container, context) {
    const filterEl = container.querySelector('#traces-filter');
    const clearBtn = filterEl.querySelector('#traces-filter-clear');

    ROOT_ACTION_TYPES.forEach(type => {
        const label = document.createElement('label');
        label.className = 'checkbox-label';

        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.value = type;
        checkbox.addEventListener('change', () => {
            if (checkbox.checked) selectedRootActionTypes.add(type);
            else selectedRootActionTypes.delete(type);
            fetchAndRender(container, context);
        });

        label.append(checkbox, document.createTextNode(' ' + rootActionLabel(type)));
        filterEl.insertBefore(label, clearBtn);
    });
}

function resetFilter(container, context) {
    selectedRootActionTypes.clear();
    container.querySelectorAll('#traces-filter input').forEach(cb => { cb.checked = false; });
    fetchAndRender(container, context);
}

async function fetchAndRender(container, context) {
    const loadingEl = container.querySelector('#traces-loading');
    const listEl = container.querySelector('#traces-list');
    const noTracesEl = container.querySelector('#no-traces');

    loadingEl.classList.remove('hidden');
    listEl.innerHTML = '';
    noTracesEl.classList.add('hidden');

    const params = {limit: 50};
    if (currentBucket !== 'all') params.bucket = currentBucket;
    if (selectedRootActionTypes.size > 0) params.rootActionType = Array.from(selectedRootActionTypes).join(',');

    try {
        const result = await context.client.get('/api/traces/insights', {params});
        if (result === null) return; // superseded by a newer request
        updateBucketCounts(container, result.bucketCounts, result.filteredBucketCounts);
        renderList(container, result, context);
    } catch (error) {
        listEl.innerHTML = `<p class="pk-empty">Failed to load traces: ${escapeHtml(error.message)}</p>`;
    } finally {
        loadingEl.classList.add('hidden');
    }
}

function updateBucketCounts(container, counts, filteredCounts) {
    if (!counts) return;
    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
        const bucket = btn.dataset.bucket;
        const label = bucket.charAt(0).toUpperCase() + bucket.slice(1);
        const count = counts[bucket];
        if (count == null) btn.textContent = label;
        else if (filteredCounts) btn.textContent = `${label} (${filteredCounts[bucket]} / ${count})`;
        else btn.textContent = `${label} (${count})`;
    });
}

function renderList(container, result, context) {
    const listEl = container.querySelector('#traces-list');
    const noTracesEl = container.querySelector('#no-traces');
    listEl.innerHTML = '';

    updateFilterIndicator(container);

    const traces = result?.traces;
    if (!traces || traces.length === 0) {
        const isFiltered = selectedRootActionTypes.size > 0;
        noTracesEl.querySelector('p').textContent = isFiltered
            ? 'No traces match the selected filters'
            : BUCKET_EMPTY_MESSAGES[currentBucket];
        noTracesEl.classList.remove('hidden');
        return;
    }

    noTracesEl.classList.add('hidden');
    traces.forEach(trace => listEl.appendChild(renderTraceItem(trace, context)));
}

function updateFilterIndicator(container) {
    const filterBanner = container.querySelector('#traces-active-filter');
    const filterText = filterBanner?.querySelector('.active-filter-text');
    const clearBtn = container.querySelector('#traces-filter-clear');
    if (!filterBanner || !filterText) return;

    if (selectedRootActionTypes.size > 0) {
        const activeFilters = Array.from(selectedRootActionTypes).map(type => rootActionLabel(type)).join(', ');
        filterText.textContent = `Filtering: Type: ${activeFilters}`;
        filterBanner.classList.remove('hidden');
        if (clearBtn) clearBtn.classList.remove('hidden');
    } else {
        filterBanner.classList.add('hidden');
        if (clearBtn) clearBtn.classList.add('hidden');
    }
}

function hasSlowIssues(span) {
    if (!span) return false;
    if ((span.issues || []).some(issue => issue.type === 'SLOW' || issue.type === 'VERY_SLOW')) return true;
    return (span.children || []).some(hasSlowIssues);
}

function renderTraceItem(trace, context) {
    const item = document.createElement('div');
    item.className = 'pk-trace-item';
    if (trace.traceId) item.dataset.traceId = trace.traceId;

    const actionType = trace.rootActionType || 'UNKNOWN';
    const hasErrors = trace.status === 'HAS_ERRORS';
    const hasSlow = hasSlowIssues(trace.rootSpan);
    const rootOperation = trace.rootOperation || '';

    const header = document.createElement('div');
    header.className = 'pk-trace-item__header';
    header.appendChild(renderMainLine(trace, actionType, hasErrors, hasSlow, rootOperation, context));
    header.appendChild(renderStats(trace, context));
    item.appendChild(header);

    header.addEventListener('click', (e) => {
        if (e.target.closest('.pk-trace-item__scheduler-link')) return;
        if (trace.traceId) openTrace(trace.traceId, context);
    });

    return item;
}

function renderMainLine(trace, actionType, hasErrors, hasSlow, rootOperation, context) {
    const mainLine = document.createElement('div');
    mainLine.className = 'pk-trace-item__main-line';

    const iconEl = document.createElement('span');
    iconEl.className = 'pk-trace-item__icon';
    iconEl.title = rootActionLabel(actionType);
    iconEl.textContent = rootActionIcon(actionType);
    mainLine.appendChild(iconEl);

    const pathEl = document.createElement('span');
    pathEl.className = 'pk-trace-item__path';
    if (rootOperation) {
        pathEl.textContent = rootOperation;
        pathEl.title = rootOperation;
    } else {
        pathEl.title = rootActionLabel(actionType);
        const labelEl = document.createElement('span');
        labelEl.className = 'pk-trace-item__action-label';
        labelEl.textContent = rootActionLabel(actionType);
        pathEl.appendChild(labelEl);
    }
    mainLine.appendChild(pathEl);

    const durationEl = document.createElement('span');
    durationEl.className = 'pk-trace-item__duration';
    durationEl.textContent = formatDurationMs(trace.durationMs);
    mainLine.appendChild(durationEl);

    if (hasErrors) mainLine.appendChild(badge('ERROR', 'error'));
    else if (hasSlow) mainLine.appendChild(badge('SLOW', 'warn'));

    if (actionType === 'SCHEDULED_JOB') mainLine.appendChild(renderSchedulerLink(context));

    return mainLine;
}

/** Plain "jump to Scheduled Tasks" navigation - see scheduled-tasks.js's own
    cross-link for why this direction carries no pre-applied filter either. */
function renderSchedulerLink(context) {
    const link = document.createElement('a');
    link.href = '#';
    link.className = 'pk-trace-item__scheduler-link';
    link.title = 'View Scheduled Tasks';
    link.textContent = '\u{1F551}';
    link.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        context.navigate('scheduled-tasks');
    });
    return link;
}

function renderStats(trace, context) {
    const stats = document.createElement('div');
    stats.className = 'pk-trace-item__stats';

    const idEl = document.createElement('code');
    idEl.className = 'pk-trace-item__id';
    idEl.textContent = trace.traceId ? trace.traceId.substring(0, 16) + '...' : 'unknown';
    stats.appendChild(idEl);

    const timeEl = document.createElement('span');
    timeEl.className = 'pk-trace-item__time';
    timeEl.textContent = trace.startTimeMs
        ? formatDateTime(trace.startTimeMs, {locale: context.locale, timeZone: context.timeZone})
        : '-';
    stats.appendChild(timeEl);

    const statParts = buildStatParts(trace);
    statParts.forEach((part, index) => {
        if (index > 0) {
            const separator = document.createElement('span');
            separator.className = 'pk-trace-item__stat-separator';
            separator.textContent = '|';
            stats.appendChild(separator);
        }
        stats.appendChild(part);
    });

    return stats;
}

function buildStatParts(trace) {
    const parts = [];

    const queryCount = trace.summary?.queries?.count || 0;
    const queryDuration = trace.summary?.queries?.totalDurationMs || 0;
    if (queryCount > 0) {
        const severity = durationSeverity(queryDuration);
        const group = document.createElement('span');
        group.className = 'pk-trace-item__stat-group' + (severity ? ` pk-trace-item__stat-group--${severity}` : '');

        const countEl = document.createElement('span');
        countEl.className = 'pk-trace-item__stat-count';
        countEl.textContent = String(queryCount);
        group.append(countEl, document.createTextNode(' ' + (queryCount === 1 ? 'query' : 'queries') + ' '));

        const durationEl = document.createElement('span');
        durationEl.className = 'pk-trace-item__stat-duration';
        durationEl.textContent = formatDurationMs(queryDuration);
        group.appendChild(durationEl);

        parts.push(group);
    }

    const errorCount = trace.summary?.logs?.errorCount || 0;
    const warnCount = trace.summary?.logs?.warnCount || 0;
    if (errorCount > 0) parts.push(logCountEl(errorCount, 'error', 'error', 'errors'));
    if (warnCount > 0) parts.push(logCountEl(warnCount, 'warn', 'warning', 'warnings'));

    return parts;
}

function logCountEl(count, modifier, singular, plural) {
    const el = document.createElement('span');
    el.className = `pk-trace-item__log-count pk-trace-item__log-count--${modifier}`;
    el.textContent = `${count} ${count === 1 ? singular : plural}`;
    return el;
}

async function openTrace(traceId, context) {
    context.navigate('traces', traceId);
    const overlay = await import('../../trace-detail/trace-detail.js');
    overlay.open(traceId, {
        // Closing the overlay (ESC, buttons) must also clean the hash, otherwise a
        // reload would unexpectedly reopen the trace - mirrors main.js's expandTraceById.
        onClose: () => {
            if (window.location.hash === `#traces/${traceId}`) context.navigate('traces');
        }
    });
}
