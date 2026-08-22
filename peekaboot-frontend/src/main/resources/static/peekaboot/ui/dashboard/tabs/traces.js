/**
 * The "Traces" tab: recent request/job/message traces, bucketed (all/errors/slow) and
 * filterable by root action type, each opening the shared trace-detail overlay when
 * clicked. Owns its bucket control, type filter and list, but exposes one entry point -
 * applyFilter() - for another tab (via context.navigate's payload) to pre-select a type
 * and root operation without reaching into this module's private filter state.
 *
 * Fetched from its own endpoint (not part of the main dashboard payload). render() is
 * called on every 30s auto-refresh cycle for every available tab regardless of which is
 * visible (see main.js's renderData()), so the actual network fetch is skipped here
 * unless this tab's container is the active one - main.js's renderTabById() calls
 * render() again the moment this tab becomes active, so switching to it never waits on
 * the next cycle. context.client's per-path generation counter is the guard against a
 * slow older response overwriting a newer one.
 */
import {badge} from '../../shared/components.js';
import {escapeHtml} from '../../shared/markup.js';
import {formatDurationMs, formatDateTime} from '../../shared/format.js';
import {durationSeverity} from '../../shared/severity.js';
import {ROOT_ACTION_TYPES, rootActionIcon, rootActionLabel} from '../../shared/root-actions.js';
import {copyableId, bindCopyables} from '../../shared/copyable.js';

export const id = 'traces';
export const label = 'Traces';

// Empty set means "no type filter" - all traces are shown.
let selectedRootActionTypes = new Set();
let currentRootOperationFilter = null;
let currentBucket = 'all';

// The most recent render() call's container/context - read by the persistent bucket/
// filter/clear listeners below (wired once, see wireControls) so a later locale or
// timezone change, or a later fetch, always uses fresh values instead of whatever was
// current the first time this tab was rendered.
let currentContainer = null;
let currentContext = null;

const BUCKET_EMPTY_MESSAGES = {
    all: 'No traces recorded',
    errors: 'No error traces recorded',
    slow: 'No slow traces recorded'
};

export function isAvailable(data, features) {
    return Boolean(features?.tracing);
}

export function render(container, data, context) {
    currentContainer = container;
    currentContext = context;
    // delegated on the panel, which survives every re-render of the trace list
    bindCopyables(container);
    wireControls(container);
    fetchAndRender();
}

/**
 * Pre-selects a root action type and/or root operation, called from another tab's
 * cross-link via context.navigate(tabId, detail, payload) - see main.js's navigate().
 * Requires at least one prior render() (always true in practice: a link that lands here
 * only ever renders once this tab has already rendered at least once - see
 * scheduled-tasks.js's own isAvailable/features gating).
 */
export function applyFilter({rootActionType, rootOperation} = {}) {
    if (!currentContainer) return;

    selectedRootActionTypes.clear();
    if (rootActionType) selectedRootActionTypes.add(rootActionType);
    currentRootOperationFilter = rootOperation || null;

    currentContainer.querySelectorAll('#traces-filter input').forEach(cb => {
        cb.checked = cb.value === rootActionType;
    });

    fetchAndRender();
}

function wireControls(container) {
    if (container.dataset.wired) return;
    container.dataset.wired = 'true';

    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
        btn.setAttribute('aria-pressed', String(btn.dataset.bucket === currentBucket));
        btn.addEventListener('click', () => {
            if (btn.dataset.bucket === currentBucket) return;
            currentBucket = btn.dataset.bucket;
            container.querySelectorAll('#traces-bucket .pk-btn').forEach(b =>
                b.setAttribute('aria-pressed', String(b === btn)));
            fetchAndRender();
        });
    });

    renderTypeFilterCheckboxes(container);

    const clearBtn = container.querySelector('#traces-filter-clear');
    if (clearBtn) clearBtn.addEventListener('click', resetFilter);
}

/** Generated from ROOT_ACTION_TYPES rather than hardcoded in index.html, so adding a
    root action type no longer means editing HTML. Each checkbox's accessible name comes
    from the wrapping <label>, matching the loggers tab's checkbox-label convention. */
function renderTypeFilterCheckboxes(container) {
    const filterEl = container.querySelector('#traces-filter');
    const clearBtn = filterEl.querySelector('#traces-filter-clear');

    ROOT_ACTION_TYPES.forEach(type => {
        const checkboxLabel = document.createElement('label');
        checkboxLabel.className = 'checkbox-label';

        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.value = type;
        checkbox.addEventListener('change', () => {
            if (checkbox.checked) selectedRootActionTypes.add(type);
            else selectedRootActionTypes.delete(type);
            fetchAndRender();
        });

        checkboxLabel.append(checkbox, document.createTextNode(' ' + rootActionLabel(type)));
        filterEl.insertBefore(checkboxLabel, clearBtn);
    });
}

function resetFilter() {
    selectedRootActionTypes.clear();
    currentRootOperationFilter = null;
    currentContainer.querySelectorAll('#traces-filter input').forEach(cb => { cb.checked = false; });
    fetchAndRender();
}

async function fetchAndRender() {
    const container = currentContainer;
    const context = currentContext;
    // Not the active tab - skip the network round trip. main.js's renderTabById() calls
    // render() (and so this) again the instant this tab is switched to, and every
    // control here (bucket/checkbox/clear) is only reachable while it is visible anyway.
    if (!container.classList.contains('active')) return;

    const loadingEl = container.querySelector('#traces-loading');
    const listEl = container.querySelector('#traces-list');
    const noTracesEl = container.querySelector('#no-traces');

    loadingEl.classList.remove('hidden');
    noTracesEl.classList.add('hidden');

    const params = {limit: 50};
    if (currentBucket !== 'all') params.bucket = currentBucket;
    if (selectedRootActionTypes.size > 0) params.rootActionType = Array.from(selectedRootActionTypes).join(',');
    if (currentRootOperationFilter) params.rootOperation = currentRootOperationFilter;

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
        const bucketLabel = bucket.charAt(0).toUpperCase() + bucket.slice(1);
        const count = counts[bucket];
        if (count == null) btn.textContent = bucketLabel;
        else if (filteredCounts) btn.textContent = `${bucketLabel} (${filteredCounts[bucket]} / ${count})`;
        else btn.textContent = `${bucketLabel} (${count})`;
    });
}

function renderList(container, result, context) {
    // Not cleared until the response is in hand (see fetchAndRender) - otherwise every
    // 30s refresh of the currently visible tab would blank the list for the network
    // round trip's duration, even though nothing about it changed.
    const listEl = container.querySelector('#traces-list');
    const noTracesEl = container.querySelector('#no-traces');
    listEl.innerHTML = '';

    updateFilterIndicator(container);

    const traces = result?.traces;
    if (!traces || traces.length === 0) {
        const isFiltered = selectedRootActionTypes.size > 0 || currentRootOperationFilter !== null;
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
    const filterText = filterBanner?.querySelector('.pk-filter-banner__text');
    const clearBtn = container.querySelector('#traces-filter-clear');
    if (!filterBanner || !filterText) return;

    const isTypeFiltered = selectedRootActionTypes.size > 0;
    const isOperationFiltered = currentRootOperationFilter !== null;
    const isFiltered = isTypeFiltered || isOperationFiltered;

    if (isFiltered) {
        let filterDescription = '';
        if (isTypeFiltered) {
            const activeFilters = Array.from(selectedRootActionTypes).map(type => rootActionLabel(type)).join(', ');
            filterDescription = `Type: ${activeFilters}`;
        }
        if (isOperationFiltered) {
            const operationLabel = currentRootOperationFilter.split('.').pop();
            filterDescription += filterDescription ? ` | Target: ${operationLabel}` : `Target: ${operationLabel}`;
        }
        filterText.textContent = `Filtering: ${filterDescription}`;
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

    // A real <button>, not the header itself: for SCHEDULED_JOB traces the header also
    // carries the scheduler link below, and a <button> cannot contain interactive
    // content - nesting the link inside one would make it unreachable to assistive tech
    // (Children Presentational: True on a role="button" div has the same effect, which
    // is why that's not used here either). The button and the link are siblings under
    // header instead, each independently focusable and named. Native Enter/Space
    // activation comes free with a real <button>, so no hand-rolled keydown handler is
    // needed, and since the link lives outside the button, no click-target guard is
    // needed either.
    const openBtn = document.createElement('button');
    openBtn.type = 'button';
    openBtn.className = 'pk-trace-item__open';
    openBtn.appendChild(renderMainLine(trace, actionType, hasErrors, hasSlow, rootOperation));
    openBtn.appendChild(renderStats(trace, context));
    if (trace.traceId) {
        openBtn.addEventListener('click', () => openTrace(trace.traceId, context));
    }
    header.appendChild(openBtn);

    if (actionType === 'SCHEDULED_JOB') header.appendChild(renderSchedulerLink(context));

    item.appendChild(header);
    return item;
}

function renderMainLine(trace, actionType, hasErrors, hasSlow, rootOperation) {
    const mainLine = document.createElement('div');
    mainLine.className = 'pk-trace-item__main-line';

    const iconEl = document.createElement('span');
    iconEl.className = 'pk-trace-item__icon';
    // role="img" + aria-label, not title: title on a roleless <span> is not reliably
    // exposed, and when rootOperation is present the action type is shown nowhere else.
    iconEl.setAttribute('role', 'img');
    iconEl.setAttribute('aria-label', rootActionLabel(actionType));
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

    return mainLine;
}

/** Plain "jump to Scheduled Tasks" navigation - the original carried no filter either
    (navigateToScheduledTasks() took no arguments), so this is a faithful move. */
function renderSchedulerLink(context) {
    const link = document.createElement('a');
    link.href = '#';
    link.className = 'pk-trace-item__scheduler-link';
    // title alone would not become the accessible name here: the emoji textContent is
    // itself real content, so it (its Unicode name) would win instead. aria-label pins
    // the name to the same text title already carries.
    link.title = 'View Scheduled Tasks';
    link.setAttribute('aria-label', 'View Scheduled Tasks');
    link.textContent = '\u{1F551}';
    // No stopPropagation needed: the link is header's sibling, not a descendant of
    // .pk-trace-item__open, so its click never reaches that button's own listener.
    link.addEventListener('click', (e) => {
        e.preventDefault();
        context.navigate('scheduled-tasks');
    });
    return link;
}

function renderStats(trace, context) {
    const stats = document.createElement('div');
    stats.className = 'pk-trace-item__stats';

    // truncated visually so a long list stays scannable, but the full id is what gets
    // copied - and clicking it copies rather than expanding the row
    stats.appendChild(copyableId(trace.traceId, {label: 'traceId', truncate: true}));

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
