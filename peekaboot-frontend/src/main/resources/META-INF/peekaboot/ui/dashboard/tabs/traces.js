/**
 * The "Traces" tab: recent request/job/message traces, bucketed (all/errors/slow) and
 * filterable by root action type, each opening the shared trace-detail overlay when
 * clicked. Owns its bucket control, type filter and list; another tab pre-selects a
 * filter here with a plain "#traces?type=...&op=..." link, which the URL reconciliation
 * below restores like any deep link.
 *
 * Fetched from its own endpoint (not part of the main dashboard payload), on the
 * self-fetching-tab.js contract: nothing is fetched or rendered while the tab is hidden,
 * and a slow older response never overwrites a newer one.
 */
import {badge, emptyState, loadingBlock, iconLink} from '../../shared/components.js';
import {el, button} from '../../shared/dom.js';
import {keyedChildren} from '../../shared/keyed.js';
import {formatDurationMs, formatDateTime, formatNumber, shortTarget} from '../../shared/format.js';
import {ROOT_ACTION_TYPES, rootActionIcon, rootActionLabel} from '../../shared/root-actions.js';
import {copyableId, bindCopyables} from '../../shared/copyable.js';
import {interruptedBadge, isSlowTrace, slowBadge, traceStatParts, truncatedBadge} from '../../shared/trace-stats.js';
import {parseAppHash, buildAppHash} from '../../shared/url-state.js';
import {urlState} from '../../shared/url-filter.js';
import {selfFetchingTab} from '../../shared/self-fetching-tab.js';

export const id = 'traces';

// Maintenance has no type chip: it is excluded by default through its own checkbox,
// which adds it to whatever the chips select once unticked.
const EXCLUDABLE_TYPE = 'MAINTENANCE';
const SELECTABLE_TYPES = ROOT_ACTION_TYPES.filter(type => type !== EXCLUDABLE_TYPE);
const TYPE_CHIPS = '#traces-filter input[name="type"]';
const EXCLUSION_LIFTED = 'none';

const PAGE_SIZE = 50;
// The listing's own bound on one answer (PageLimit.MAX).
const MAX_DEPTH = 10000;

// What a row's click shortcut leaves alone; the open button itself is one, so its own click is not repeated.
const INTERACTIVE_ELEMENTS = 'a, button, input, select, textarea, label, summary, [tabindex]';

const BUCKET_EMPTY_MESSAGES = {
    all: 'No traces recorded',
    errors: 'No error traces recorded',
    slow: 'No slow traces recorded'
};

export function isAvailable(data, features) {
    return Boolean(features?.tracing);
}

export function create(container) {
    // Empty set with the exclusion in place means no type in the request, which the backend
    // answers with its default view - every type except the excludable one.
    let selectedRootActionTypes = new Set();
    let maintenanceExcluded = true;
    let currentRootOperationFilter = null;
    let currentBucket = 'all';
    // The rows the listing asks for: one page, and one more with every "Load more" on this filter.
    let depth = PAGE_SIZE;

    const tab = selfFetchingTab({
        fetch: context => context.client.get('/api/traces', {params: requestParams()}),
        // Only before the first list arrives: the block sits above the controls, so showing it
        // for a refresh would push them and the list down for the round trip.
        loading: (panel, {firstLoad}) => {
            if (!firstLoad) return;
            container.querySelector('#traces-loading').classList.remove('hidden');
            container.querySelector('#no-traces').classList.add('hidden');
        },
        renderResult: (panel, result, context) => {
            updateBucketCounts(result.bucketCounts, result.filteredBucketCounts, context.locale);
            renderList(result, context);
            renderLoadMore(result, context.locale);
            container.querySelector('#traces-loading').classList.add('hidden');
        },
        renderError: (panel, error) => {
            container.querySelector('#traces-list').replaceChildren(emptyState(`Failed to load traces: ${error.message}`));
            container.querySelector('#traces-loading').classList.add('hidden');
            // Both describe the list the error just replaced.
            container.querySelector('#no-traces').classList.add('hidden');
            container.querySelector('#traces-more').classList.add('hidden');
        }
    });

    /** Bucket, types, root operation and the exclusion as URL state (see shared/url-filter.js). */
    const filterState = urlState({
        keys: ['bucket', 'type', 'op', 'excluded'],
        read: filterFromUrl,
        apply: applyFilter,
        params: filterParams
    });

    // delegated on the panel, which survives every re-render of the trace list
    bindCopyables(container);
    wireControls();

    function render(data, context) {
        if (!context.features?.tracingSpansPossible) {
            showWhyNoTracesCanAppear(container);
            return;
        }
        reconcileWithUrl(context);
        return tab.load(container, context);
    }

    /**
     * A detail segment in the hash means the overlay owns the params slot: no seed, no write
     * (main.js's setUrlParams drops the write side of the same rule) - the overlay's own
     * level/q params are not this tab's bucket/type/op.
     */
    function reconcileWithUrl(context) {
        if (parseAppHash().detail) return;
        filterState.reconcile(context);
    }

    /**
     * Makes the given filter the current one and shows it on the controls. Compares against
     * the current state rather than unconditionally overwriting it, so this is a no-op once
     * the URL already matches (the steady state on every render while a filter is active).
     */
    function applyFilter(filter) {
        const currentTypesJoined = Array.from(selectedRootActionTypes).sort().join(',');
        const typesJoined = [...filter.types].sort().join(',');
        if (filter.bucket === currentBucket && typesJoined === currentTypesJoined
            && filter.rootOperation === currentRootOperationFilter
            && filter.maintenanceExcluded === maintenanceExcluded) {
            return;
        }

        currentBucket = filter.bucket;
        selectedRootActionTypes = new Set(filter.types);
        currentRootOperationFilter = filter.rootOperation;
        maintenanceExcluded = filter.maintenanceExcluded;
        depth = PAGE_SIZE;

        syncBucketButtons();
        syncFilterCheckboxes();
    }

    function syncBucketButtons() {
        container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn =>
            btn.setAttribute('aria-pressed', String(btn.dataset.bucket === currentBucket)));
    }

    function syncFilterCheckboxes() {
        container.querySelectorAll(TYPE_CHIPS).forEach(cb => {
            cb.checked = selectedRootActionTypes.has(cb.value);
        });
        container.querySelector('#traces-filter-exclude-maintenance').checked = maintenanceExcluded;
    }

    function isUserFiltered() {
        return selectedRootActionTypes.size > 0 || currentRootOperationFilter !== null || !maintenanceExcluded;
    }

    /**
     * The current filter as URL params, omitting each key that's at its default so a clean
     * filter yields a clean "#traces" hash.
     */
    function filterParams() {
        const params = {};
        if (currentBucket !== 'all') params.bucket = currentBucket;
        if (selectedRootActionTypes.size > 0) params.type = Array.from(selectedRootActionTypes).join(',');
        if (currentRootOperationFilter) params.op = currentRootOperationFilter;
        if (!maintenanceExcluded) params.excluded = EXCLUSION_LIFTED;
        return params;
    }

    /** The listing request's query, from the same state the URL params are written from. */
    function requestParams() {
        const params = {limit: depth};
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

    function wireControls() {
        container.querySelector('#traces-loading').appendChild(loadingBlock('Loading traces...'));

        syncBucketButtons();
        container.querySelectorAll('#traces-bucket .pk-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                if (btn.dataset.bucket === currentBucket) return;
                currentBucket = btn.dataset.bucket;
                syncBucketButtons();
                listChangedFilter();
            });
        });

        renderTypeFilterCheckboxes();

        const clearBtn = container.querySelector('#traces-filter-clear');
        if (clearBtn) clearBtn.addEventListener('click', resetFilter);

        container.querySelector('#traces-load-more').addEventListener('click', () => {
            // capped here as well as by hiding the button, which a second click can beat
            depth = Math.min(depth + PAGE_SIZE, MAX_DEPTH);
            tab.refetch();
        });
    }

    /** For a control that just changed the filter: the URL follows, and the list starts over at one page. */
    function listChangedFilter() {
        depth = PAGE_SIZE;
        filterState.save(tab.context());
        tab.refetch();
    }

    /**
     * Generated from ROOT_ACTION_TYPES rather than hardcoded in index.html, so adding a
     * root action type means no HTML edit. Each type checkbox's accessible name comes
     * from the wrapping <label>, matching the loggers tab's checkbox-label convention; the
     * exclusion checkbox's aria-label overrides it.
     */
    function renderTypeFilterCheckboxes() {
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
            listChangedFilter();
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
        syncFilterCheckboxes();
        listChangedFilter();
    }

    /**
     * The plain number is what the list can actually show - the backend's default view
     * already leaves its hidden types out, so the filtered count is the truthful one. The
     * "shown / total" pair appears only for a filter the user chose, with the store's full
     * count (hidden types included) as the total.
     */
    function updateBucketCounts(counts, filteredCounts, locale) {
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

    function renderList(result, context) {
        const listEl = container.querySelector('#traces-list');
        const noTracesEl = container.querySelector('#no-traces');

        updateFilterIndicator();

        const traces = result?.traces;
        if (!traces || traces.length === 0) {
            listEl.replaceChildren();
            noTracesEl.querySelector('p').textContent = isUserFiltered()
                ? 'No traces match the selected filters'
                : BUCKET_EMPTY_MESSAGES[currentBucket];
            noTracesEl.classList.remove('hidden');
            return;
        }

        noTracesEl.classList.add('hidden');
        // By key, so the 30s refresh leaves the row of a trace that did not change, and focus in it, alone.
        keyedChildren(listEl, traces, {
            key: row => `${row.summary.traceId}/${row.summary.subtree?.rootSpanId ?? ''}`,
            signature: row => JSON.stringify([row.summary, context.locale, context.timeZone]),
            build: row => renderTraceItem(row.summary, context)
        });
    }

    /**
     * Shown under the list while the answer says older rows exist: how many rows are listed, and
     * the button that lists a page more. It gives no total, because the bucket counts are traces
     * and an async task lists as a row of its own.
     */
    function renderLoadMore(result, locale) {
        const loadMoreBtn = container.querySelector('#traces-load-more');
        const olderRowsExist = result.next != null;
        const atMaxDepth = depth >= MAX_DEPTH;
        const buttonHadFocus = document.activeElement === loadMoreBtn;

        container.querySelector('#traces-more').classList.toggle('hidden', !olderRowsExist);
        loadMoreBtn.classList.toggle('hidden', atMaxDepth);
        if (olderRowsExist) {
            const shown = `Showing the ${formatNumber(result.traces.length, {locale})} newest rows`;
            container.querySelector('#traces-shown').textContent = atMaxDepth
                ? `${shown}. Narrow the filter to reach older ones.`
                : shown;
        }
        // A button that hides drops focus to the top of the page. The reader was at the end of the list.
        if (buttonHadFocus && (!olderRowsExist || atMaxDepth)) {
            container.querySelector('#traces-list .pk-trace-item:last-child .pk-trace-item__open')?.focus();
        }
    }

    function updateFilterIndicator() {
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

    return {show: render, update: render, hide: tab.stop};
}

/**
 * The store exists but no span source feeds it, so it can never hold a trace: the
 * explanation replaces the controls and the list, and nothing is fetched.
 */
function showWhyNoTracesCanAppear(container) {
    Array.from(container.children).forEach(child =>
        child.classList.toggle('hidden', child.id !== 'traces-unavailable'));
}

/** The filter the URL params name, a missing or unrecognized value at its default. */
function filterFromUrl(urlParams) {
    // Validated against the canonical lists, since the backend refuses an unrecognized bucket
    // or type (a typo, a stale link) and the list would fail to load.
    // The case fold mirrors the backend's own.
    return {
        bucket: Object.keys(BUCKET_EMPTY_MESSAGES).includes(urlParams.bucket) ? urlParams.bucket : 'all',
        types: (urlParams.type ? urlParams.type.split(',') : [])
            .map(type => type.toUpperCase())
            .filter(type => SELECTABLE_TYPES.includes(type)),
        rootOperation: urlParams.op || null,
        maintenanceExcluded: urlParams.excluded !== EXCLUSION_LIFTED
    };
}

function renderTraceItem(trace, context) {
    const actionType = trace.rootActionType || 'UNKNOWN';
    const hasErrors = trace.outcome === 'ERROR';
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

    // On a whole trace the SLOW reason is the Slow bucket's own rule, so the badge and the bucket agree.
    let verdict = null;
    if (hasErrors) verdict = badge('ERROR', 'error');
    else if (isSlowTrace(trace)) verdict = slowBadge();

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
        // the overlay header's wording, so the row and the detail agree
        trace.outcome === 'INTERRUPTED' ? interruptedBadge() : null,
        trace.counts.droppedSpans > 0 ? truncatedBadge() : null);
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
    const startTime = trace.startTime
        ? formatDateTime(trace.startTime, {locale: context.locale, timeZone: context.timeZone})
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
