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
import {el, button} from '../../shared/dom.js';
import {formatDurationMs, formatDateTime, formatNumber, shortTarget} from '../../shared/format.js';
import {ROOT_ACTION_TYPES, rootActionIcon, rootActionLabel} from '../../shared/root-actions.js';
import {copyableId, bindCopyables} from '../../shared/copyable.js';
import {slowBadge, traceStatParts, truncatedBadge} from '../../shared/trace-stats.js';
import {parseAppHash, buildAppHash} from '../../shared/url-state.js';
import {reconcileFilterWithUrl} from '../../shared/url-filter.js';
import {selfFetchingTab} from '../../shared/self-fetching-tab.js';

export const id = 'traces';

// Maintenance has no type chip: it is excluded by default through its own checkbox,
// which adds it to whatever the chips select once unticked.
const EXCLUDABLE_TYPE = 'MAINTENANCE';
const SELECTABLE_TYPES = ROOT_ACTION_TYPES.filter(type => type !== EXCLUDABLE_TYPE);
const TYPE_CHIPS = '#traces-filter input[name="type"]';
const EXCLUSION_LIFTED = 'none';

// Empty set with the exclusion in place means no type in the request, which the backend
// answers with its default view - every type except the excludable one.
let selectedRootActionTypes = new Set();
let maintenanceExcluded = true;
let currentRootOperationFilter = null;
let currentBucket = 'all';

// What a row's click shortcut leaves alone; the open button itself is one, so its own click is not repeated.
const INTERACTIVE_ELEMENTS = 'a, button, input, select, textarea, label, summary, [tabindex]';

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
        updateBucketCounts(container, result.bucketCounts, result.filteredBucketCounts, context.locale);
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
    if (!context.features?.tracingSpansPossible) {
        showWhyNoTracesCanAppear(container);
        return;
    }
    // delegated on the panel, which survives every re-render of the trace list
    bindCopyables(container);
    wireControls(container);
    tab.render(container, data, context);
}

/**
 * The store exists but no span source feeds it, so it can never hold a trace: the
 * explanation replaces the controls and the list, and nothing is fetched.
 */
function showWhyNoTracesCanAppear(container) {
    Array.from(container.children).forEach(child =>
        child.classList.toggle('hidden', child.id !== 'traces-unavailable'));
}

/**
 * Reconciles bucket/type/op with the URL by the shared two-direction rule (see
 * url-filter.js). A detail segment in the hash means the overlay owns the params slot:
 * no seed, no write (main.js's setUrlParams drops the write side of the same rule) -
 * the overlay's own level/q params are not this tab's bucket/type/op.
 */
function reconcileWithUrl(container, context) {
    if (parseAppHash().detail) return;

    reconcileFilterWithUrl(context, ['bucket', 'type', 'op', 'excluded'], {
        seed: params => {
            seedFromUrl(container, params);
            // corrects a bogus or non-canonical value in the URL to the state that actually restored
            writeUrlParams();
        },
        hasNonDefaultState: () => currentBucket !== 'all' || isUserFiltered(),
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
        .filter(type => SELECTABLE_TYPES.includes(type));
    const urlOp = params.op || null;
    const urlMaintenanceExcluded = params.excluded !== EXCLUSION_LIFTED;

    const currentTypesJoined = Array.from(selectedRootActionTypes).sort().join(',');
    const urlTypesJoined = [...urlTypes].sort().join(',');
    if (urlBucket === currentBucket && urlTypesJoined === currentTypesJoined && urlOp === currentRootOperationFilter
        && urlMaintenanceExcluded === maintenanceExcluded) {
        return;
    }

    currentBucket = urlBucket;
    selectedRootActionTypes = new Set(urlTypes);
    currentRootOperationFilter = urlOp;
    maintenanceExcluded = urlMaintenanceExcluded;

    syncBucketButtons(container);
    syncFilterCheckboxes(container);
}

function syncBucketButtons(container) {
    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn =>
        btn.setAttribute('aria-pressed', String(btn.dataset.bucket === currentBucket)));
}

function syncFilterCheckboxes(container) {
    container.querySelectorAll(TYPE_CHIPS).forEach(cb => {
        cb.checked = selectedRootActionTypes.has(cb.value);
    });
    container.querySelector('#traces-filter-exclude-maintenance').checked = maintenanceExcluded;
}

function isUserFiltered() {
    return selectedRootActionTypes.size > 0 || currentRootOperationFilter !== null || !maintenanceExcluded;
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
    if (!maintenanceExcluded) params.excluded = EXCLUSION_LIFTED;
    tab.context().setUrlParams(params);
}

/** The listing request's query, from the same state the URL params are written from. */
function requestParams() {
    const params = {limit: 50};
    if (currentBucket !== 'all') params.bucket = currentBucket;
    const rootActionType = requestedRootActionType();
    if (rootActionType) params.rootActionType = rootActionType;
    if (currentRootOperationFilter) params.rootOperation = currentRootOperationFilter;
    return params;
}

/** Null asks for the backend's default view, '*' for every type. */
function requestedRootActionType() {
    if (maintenanceExcluded) {
        return selectedRootActionTypes.size > 0 ? Array.from(selectedRootActionTypes).join(',') : null;
    }
    return selectedRootActionTypes.size > 0 ? [...selectedRootActionTypes, EXCLUDABLE_TYPE].join(',') : '*';
}

function wireControls(container) {
    if (container.dataset.wired) return;
    container.dataset.wired = 'true';

    container.querySelector('#traces-loading').appendChild(loadingBlock('Loading traces...'));

    syncBucketButtons(container);
    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            if (btn.dataset.bucket === currentBucket) return;
            currentBucket = btn.dataset.bucket;
            syncBucketButtons(container);
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
 * root action type means no HTML edit. Each type checkbox's accessible name comes
 * from the wrapping <label>, matching the loggers tab's checkbox-label convention; the
 * exclusion checkbox's aria-label overrides it.
 */
function renderTypeFilterCheckboxes(container) {
    const filterEl = container.querySelector('#traces-filter');
    const excludedGroup = filterEl.querySelector('#traces-filter-excluded');

    SELECTABLE_TYPES.forEach(type => {
        const checkbox = filterCheckbox(type, () => {
            if (checkbox.checked) selectedRootActionTypes.add(type);
            else selectedRootActionTypes.delete(type);
        });
        checkbox.name = 'type';
        filterEl.insertBefore(checkbox.parentElement, excludedGroup);
    });

    const exclusion = filterCheckbox(EXCLUDABLE_TYPE, () => {
        maintenanceExcluded = exclusion.checked;
    });
    exclusion.id = 'traces-filter-exclude-maintenance';
    // Checked excludes here and includes on every box beside it, so the verb is in the name.
    exclusion.setAttribute('aria-label', `Exclude ${rootActionLabel(EXCLUDABLE_TYPE)}`);
    exclusion.checked = maintenanceExcluded;
    excludedGroup.append(exclusion.parentElement);
}

/** A labelled checkbox that applies its change to the filter state, then refetches. */
function filterCheckbox(type, applyChange) {
    const checkbox = el('input', {attrs: {type: 'checkbox', value: type}});
    checkbox.addEventListener('change', () => {
        applyChange();
        writeUrlParams();
        tab.refetch();
    });

    // The label wraps the checkbox, which is how the caller reaches it (parentElement).
    // The icon and text are one flex item, so the icon sits a space from its label rather than a flex gap.
    el('label', {className: 'pk-checkbox-label'}, checkbox,
        el('span', {}, el('span', {text: rootActionIcon(type), attrs: {'aria-hidden': 'true'}}), ' ' + rootActionLabel(type)));
    return checkbox;
}

function resetFilter() {
    selectedRootActionTypes.clear();
    currentRootOperationFilter = null;
    maintenanceExcluded = true;
    syncFilterCheckboxes(tab.container());
    writeUrlParams();
    tab.refetch();
}

/**
 * The plain number is what the list can actually show - the backend's default view
 * already leaves its hidden types out, so the filtered count is the truthful one. The
 * "shown / total" pair appears only for a filter the user chose, with the store's full
 * count (hidden types included) as the total.
 */
function updateBucketCounts(container, counts, filteredCounts, locale) {
    if (!counts) return;
    const userFiltered = isUserFiltered();
    const grouped = n => formatNumber(n, {locale});
    container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
        const bucket = btn.dataset.bucket;
        const bucketLabel = bucket.charAt(0).toUpperCase() + bucket.slice(1);
        const count = counts[bucket];

        let label;
        if (count == null) {
            label = bucketLabel;
        } else if (userFiltered && filteredCounts) {
            label = `${bucketLabel} (${grouped(filteredCounts[bucket])} / ${grouped(count)})`;
        } else if (filteredCounts) {
            label = `${bucketLabel} (${grouped(filteredCounts[bucket])})`;
        } else {
            label = `${bucketLabel} (${grouped(count)})`;
        }
        btn.textContent = label;
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
        noTracesEl.querySelector('p').textContent = isUserFiltered()
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
    if (!filterBanner || !filterText) return;

    if (isUserFiltered()) {
        const filterParts = [];
        if (selectedRootActionTypes.size > 0) {
            const activeFilters = Array.from(selectedRootActionTypes).map(type => rootActionLabel(type)).join(', ');
            filterParts.push(`Type: ${activeFilters}`);
        }
        if (currentRootOperationFilter !== null) {
            filterParts.push(`Target: ${shortTarget(currentRootOperationFilter)}`);
        }
        if (!maintenanceExcluded) {
            filterParts.push(`${rootActionLabel(EXCLUDABLE_TYPE)} included`);
        }
        filterText.textContent = `Filtering: ${filterParts.join(' | ')}`;
        filterBanner.classList.remove('hidden');
    } else {
        filterBanner.classList.add('hidden');
    }
}

function renderTraceItem(trace, context) {
    const actionType = trace.rootActionType || 'UNKNOWN';
    const hasErrors = trace.status === 'HAS_ERRORS';
    const rootOperation = trace.rootOperation || '';

    // A real <button> over the main line alone. The scheduler link and the stats line are
    // its siblings, not its children: the stats carry the traceId's copy control, and a
    // button cannot contain interactive content (see ToolbarShell for the same shape).
    const openBtn = button({className: 'pk-unbutton pk-trace-item__open'},
        renderMainLine(trace, actionType, hasErrors, rootOperation));

    const header = el('div', {className: 'pk-trace-item__header'},
        el('div', {className: 'pk-trace-item__body'}, openBtn, renderStats(trace, context)),
        actionType === 'SCHEDULED_JOB' ? renderSchedulerLink() : null,
        trace.subtree?.enclosedByStoredTrace ? renderEnclosingTraceLink(trace.traceId) : null);
    if (trace.traceId) {
        openBtn.addEventListener('click', () => context.openTrace(trace.traceId, trace.subtree?.rootSpanId));
        forwardRowClicks(header, openBtn);
    }

    return el('div', {
        className: 'pk-trace-item',
        attrs: {
            'data-trace-id': trace.traceId || null,
            // An async row shares its trace's id with that trace's own whole-trace row (one bundle,
            // several listing rows) - the subtree's root span id is what tells the two apart in the
            // DOM, for anything, tests included, that needs to address one specific row.
            'data-subtree-root-span-id': trace.subtree?.rootSpanId || null
        }
    }, header);
}

/**
 * Makes a click anywhere on `row` a pointer shortcut to `openBtn`, which stays the row's one
 * tab stop. A click on another control in the row stays that control's, and the click that
 * ends a drag-select selects rather than opens.
 */
function forwardRowClicks(row, openBtn) {
    row.addEventListener('click', event => {
        const control = event.target.closest(INTERACTIVE_ELEMENTS);
        if ((control && row.contains(control)) || !document.getSelection().isCollapsed) return;
        openBtn.click();
    });
}

function renderMainLine(trace, actionType, hasErrors, rootOperation) {
    const actionLabel = rootActionLabel(actionType);
    const path = rootOperation
        ? el('span', {className: 'pk-trace-item__path', text: rootOperation, title: rootOperation})
        : el('span', {className: 'pk-trace-item__path', title: actionLabel},
            el('span', {className: 'pk-trace-item__action-label', text: actionLabel}));

    // trace.slow is the backend's verdict: some span carries a SLOW or VERY_SLOW issue.
    // Not the Slow bucket - that admits a trace by its total duration.
    let verdict = null;
    if (hasErrors) verdict = badge('ERROR', 'error');
    else if (trace.slow) verdict = slowBadge();

    return el('div', {className: 'pk-trace-item__main-line'},
        // role="img" + aria-label, not title: title on a roleless <span> is not reliably
        // exposed, and when rootOperation is present the action type is shown nowhere else.
        el('span', {
            className: 'pk-trace-item__icon',
            text: rootActionIcon(actionType),
            title: actionLabel,
            attrs: {role: 'img', 'aria-label': actionLabel}
        }),
        path,
        el('span', {className: 'pk-trace-item__duration', text: formatDurationMs(trace.durationMs)}),
        verdict,
        trace.truncated ? truncatedBadge() : null);
}

/** Plain "jump to Scheduled Tasks" link, deliberately unfiltered. A sibling of the open button, not a descendant, so its click never reaches that button's listener. */
function renderSchedulerLink() {
    return iconLink(buildAppHash({tab: 'scheduled-tasks'}), {
        label: 'View Scheduled Tasks',
        icon: '\u{1F551}',
        className: 'pk-trace-item__scheduler-link'
    });
}

/** "This ran under a bigger trace" - a sibling of the open button, for the same reason the scheduler link is. */
function renderEnclosingTraceLink(traceId) {
    return iconLink(buildAppHash({tab: 'traces', detail: traceId}), {
        label: 'View the trace this ran under',
        icon: '\u{2934}',
        className: 'pk-trace-item__enclosing-link'
    });
}

function renderStats(trace, context) {
    const startTime = trace.startTimeMs
        ? formatDateTime(trace.startTimeMs, {locale: context.locale, timeZone: context.timeZone})
        : '-';
    const parts = traceStatParts(trace, {features: context.features, locale: context.locale});

    return el('div', {className: 'pk-trace-item__stats'},
        // truncated visually so a long list stays scannable, but the full id is what gets
        // copied - and clicking it copies rather than expanding the row
        copyableId(trace.traceId, {label: 'traceId', truncate: true}),
        el('span', {className: 'pk-trace-item__time', text: startTime}),
        ...parts.flatMap((part, index) => index > 0
            ? [el('span', {className: 'pk-trace-item__stat-separator', text: '|'}), part]
            : [part]));
}
