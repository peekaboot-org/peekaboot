/**
 * The "Traces" tab: recent request/job/message traces, bucketed (all/errors/slow) and
 * filterable by root action type, each opening the shared trace-detail overlay when
 * clicked. Owns its bucket control, type filter and list; another tab pre-selects a
 * filter here with a plain "#traces?type=...&op=..." link, which the URL reconciliation
 * below restores like any deep link.
 *
 * Fetched from its own endpoint (not part of the main dashboard payload), on the
 * self-fetching-tab.js contract: a background render skips the round trip, and a slow
 * older response never overwrites a newer one.
 */
import {badge, emptyState, loadingBlock, iconLink} from '../../shared/components.js';
import {formatDurationMs, formatDateTime} from '../../shared/format.js';
import {ROOT_ACTION_TYPES, rootActionIcon, rootActionLabel} from '../../shared/root-actions.js';
import {copyableId, bindCopyables} from '../../shared/copyable.js';
import {traceStatParts} from '../../shared/trace-stats.js';
import {parseAppHash, buildAppHash} from '../../shared/url-state.js';
import {reconcileFilterWithUrl} from '../../shared/url-filter.js';
import {selfFetchingTab} from '../../shared/self-fetching-tab.js';

export const id = 'traces';
export const label = 'Traces';

// Empty set means no type in the request, which the backend answers with its default
// view - every type except the routine pool maintenance it keeps out. Every chip,
// including Connection Pool's, then works like any other type's.
let selectedRootActionTypes = new Set();
let currentRootOperationFilter = null;
let currentBucket = 'all';

const BUCKET_EMPTY_MESSAGES = {
    all: 'No traces recorded',
    errors: 'No error traces recorded',
    slow: 'No slow traces recorded'
};

const tab = selfFetchingTab({
    fetch: context => context.client.get('/api/traces/insights', {params: requestParams()}),
    reconcile: reconcileWithUrl,
    loading: container => {
        container.querySelector('#traces-loading').classList.remove('hidden');
        container.querySelector('#no-traces').classList.add('hidden');
    },
    renderResult: (container, result, context) => {
        updateBucketCounts(container, result.bucketCounts, result.filteredBucketCounts);
        renderList(container, result, context);
        container.querySelector('#traces-loading').classList.add('hidden');
    },
    renderError: (container, error) => {
        container.querySelector('#traces-list').replaceChildren(emptyState(`Failed to load traces: ${error.message}`));
        container.querySelector('#traces-loading').classList.add('hidden');
    }
});

export function isAvailable(data, features) {
    return Boolean(features?.tracing);
}

export function render(container, data, context) {
    // delegated on the panel, which survives every re-render of the trace list
    bindCopyables(container);
    wireControls(container);
    tab.render(container, data, context);
}

/**
 * Reconciles bucket/type/op with the URL by the shared two-direction rule (see
 * url-filter.js). A detail segment in the hash means the overlay owns the params slot:
 * no seed, no write (main.js's setUrlParams drops the write side of the same rule) -
 * the overlay's own level/q params are not this tab's bucket/type/op.
 */
function reconcileWithUrl(container, context) {
    if (parseAppHash().detail) return;

    reconcileFilterWithUrl(context, ['bucket', 'type', 'op'], {
        seed: params => {
            seedFromUrl(container, params);
            // corrects a bogus or non-canonical value in the URL to the state that actually restored
            writeUrlParams();
        },
        hasNonDefaultState: () => currentBucket !== 'all' || selectedRootActionTypes.size > 0 || Boolean(currentRootOperationFilter),
        writeBack: writeUrlParams
    });
}

/**
 * Restores bucket/type/op state from the URL. Compares against the current state
 * rather than unconditionally overwriting it, so this is a no-op once the URL already
 * matches (the steady state on every render while this tab's own filter is active).
 */
function seedFromUrl(container, params) {
    // Validated against the canonical lists - an unrecognized bucket (a typo, a stale link)
    // would otherwise sail straight through to the backend and back as a literal "undefined"
    // in the empty-state message, and an unknown type would leave the backend on its
    // default view while the banner here still claimed a filter. The case fold mirrors
    // the backend's own.
    const urlBucket = Object.keys(BUCKET_EMPTY_MESSAGES).includes(params.bucket) ? params.bucket : 'all';
    const urlTypes = (params.type ? params.type.split(',') : [])
        .map(type => type.toUpperCase())
        .filter(type => ROOT_ACTION_TYPES.includes(type));
    const urlOp = params.op || null;

    const currentTypesJoined = Array.from(selectedRootActionTypes).sort().join(',');
    const urlTypesJoined = [...urlTypes].sort().join(',');
    if (urlBucket === currentBucket && urlTypesJoined === currentTypesJoined && urlOp === currentRootOperationFilter) {
        return;
    }

    currentBucket = urlBucket;
    selectedRootActionTypes = new Set(urlTypes);
    currentRootOperationFilter = urlOp;

    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn =>
        btn.setAttribute('aria-pressed', String(btn.dataset.bucket === currentBucket)));
    container.querySelectorAll('#traces-filter input').forEach(cb => {
        cb.checked = selectedRootActionTypes.has(cb.value);
    });
}

/**
 * Writes the current bucket/type/op filter state back to the URL, omitting each key
 * that's at its default so a clean filter yields a clean "#traces" hash.
 */
function writeUrlParams() {
    const params = {};
    if (currentBucket !== 'all') params.bucket = currentBucket;
    if (selectedRootActionTypes.size > 0) params.type = Array.from(selectedRootActionTypes).join(',');
    if (currentRootOperationFilter) params.op = currentRootOperationFilter;
    tab.context().setUrlParams(params);
}

/** The listing request's query, from the same state the URL params are written from. */
function requestParams() {
    const params = {limit: 50};
    if (currentBucket !== 'all') params.bucket = currentBucket;
    if (selectedRootActionTypes.size > 0) params.rootActionType = Array.from(selectedRootActionTypes).join(',');
    if (currentRootOperationFilter) params.rootOperation = currentRootOperationFilter;
    return params;
}

function wireControls(container) {
    if (container.dataset.wired) return;
    container.dataset.wired = 'true';

    container.querySelector('#traces-loading').appendChild(loadingBlock('Loading traces...'));

    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
        btn.setAttribute('aria-pressed', String(btn.dataset.bucket === currentBucket));
        btn.addEventListener('click', () => {
            if (btn.dataset.bucket === currentBucket) return;
            currentBucket = btn.dataset.bucket;
            container.querySelectorAll('#traces-bucket .pk-btn').forEach(b =>
                b.setAttribute('aria-pressed', String(b === btn)));
            writeUrlParams();
            tab.refetch();
        });
    });

    renderTypeFilterCheckboxes(container);

    const clearBtn = container.querySelector('#traces-filter-clear');
    if (clearBtn) clearBtn.addEventListener('click', resetFilter);
}

/**
 * Generated from ROOT_ACTION_TYPES rather than hardcoded in index.html, so adding a
 * root action type means no HTML edit. Each checkbox's accessible name comes
 * from the wrapping <label>, matching the loggers tab's checkbox-label convention.
 */
function renderTypeFilterCheckboxes(container) {
    const filterEl = container.querySelector('#traces-filter');
    const clearBtn = filterEl.querySelector('#traces-filter-clear');

    ROOT_ACTION_TYPES.forEach(type => {
        const checkboxLabel = document.createElement('label');
        checkboxLabel.className = 'pk-checkbox-label';

        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.value = type;
        checkbox.addEventListener('change', () => {
            if (checkbox.checked) selectedRootActionTypes.add(type);
            else selectedRootActionTypes.delete(type);
            writeUrlParams();
            tab.refetch();
        });

        checkboxLabel.append(checkbox, document.createTextNode(' ' + rootActionLabel(type)));
        filterEl.insertBefore(checkboxLabel, clearBtn);
    });
}

function resetFilter() {
    selectedRootActionTypes.clear();
    currentRootOperationFilter = null;
    tab.container().querySelectorAll('#traces-filter input').forEach(cb => { cb.checked = false; });
    writeUrlParams();
    tab.refetch();
}

/**
 * The plain number is what the list can actually show - the backend's default view
 * already leaves its hidden types out, so the filtered count is the truthful one. The
 * "shown / total" pair appears only for a filter the user chose, with the store's full
 * count (hidden types included) as the total.
 */
function updateBucketCounts(container, counts, filteredCounts) {
    if (!counts) return;
    const userFiltered = selectedRootActionTypes.size > 0 || currentRootOperationFilter !== null;
    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
        const bucket = btn.dataset.bucket;
        const bucketLabel = bucket.charAt(0).toUpperCase() + bucket.slice(1);
        const count = counts[bucket];
        if (count == null) btn.textContent = bucketLabel;
        else if (userFiltered && filteredCounts) btn.textContent = `${bucketLabel} (${filteredCounts[bucket]} / ${count})`;
        else if (filteredCounts) btn.textContent = `${bucketLabel} (${filteredCounts[bucket]})`;
        else btn.textContent = `${bucketLabel} (${count})`;
    });
}

function renderList(container, result, context) {
    // Not cleared until the response is in hand - otherwise every 30s refresh of the
    // currently visible tab would blank the list for the network round trip's duration,
    // even though nothing about it changed.
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

function renderTraceItem(trace, context) {
    const item = document.createElement('div');
    item.className = 'pk-trace-item';
    if (trace.traceId) item.dataset.traceId = trace.traceId;

    const actionType = trace.rootActionType || 'UNKNOWN';
    const hasErrors = trace.status === 'HAS_ERRORS';
    const rootOperation = trace.rootOperation || '';

    const header = document.createElement('div');
    header.className = 'pk-trace-item__header';

    // A real <button>; the scheduler link below is its sibling, not its child, because a
    // button cannot contain interactive content (see ToolbarShell for the same shape).
    const openBtn = document.createElement('button');
    openBtn.type = 'button';
    openBtn.className = 'pk-unbutton pk-trace-item__open';
    openBtn.appendChild(renderMainLine(trace, actionType, hasErrors, rootOperation));
    openBtn.appendChild(renderStats(trace, context));
    if (trace.traceId) {
        openBtn.addEventListener('click', () => context.openTrace(trace.traceId));
    }
    header.appendChild(openBtn);

    if (actionType === 'SCHEDULED_JOB') header.appendChild(renderSchedulerLink());

    item.appendChild(header);
    return item;
}

function renderMainLine(trace, actionType, hasErrors, rootOperation) {
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

    // trace.slow is the backend's verdict: some span carries a SLOW or VERY_SLOW issue.
    // Not the Slow bucket - that admits a trace by its total duration.
    if (hasErrors) mainLine.appendChild(badge('ERROR', 'error'));
    else if (trace.slow) mainLine.appendChild(badge('SLOW', 'warn'));

    if (trace.truncated) {
        const truncatedBadge = badge('TRUNCATED', 'warn');
        truncatedBadge.title = 'This trace hit the max-spans-per-trace cap - the oldest spans were dropped.';
        mainLine.appendChild(truncatedBadge);
    }

    return mainLine;
}

/** Plain "jump to Scheduled Tasks" link, deliberately unfiltered. A sibling of the open button, not a descendant, so its click never reaches that button's listener. */
function renderSchedulerLink() {
    return iconLink(buildAppHash({tab: 'scheduled-tasks'}), {
        label: 'View Scheduled Tasks',
        icon: '\u{1F551}',
        className: 'pk-trace-item__scheduler-link'
    });
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

    traceStatParts(trace, context.features).forEach((part, index) => {
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
