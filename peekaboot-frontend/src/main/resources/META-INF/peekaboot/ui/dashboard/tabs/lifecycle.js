/**
 * The "Lifecycle" tab: the application's start/stop history turned into runs, one row
 * per run, newest first (the server already orders /api/lifecycle/runs that way).
 * Paired with Insights in main.js's TABS order - both are views of the application over
 * time, Insights on what it measured, this on when it existed.
 *
 * Fetched from its own endpoint; a background render skips the round trip (active-tab
 * guard, see main.js's renderTab).
 *
 * The current page is module-level state, not derived from the fetch response, so it
 * survives across those 30s re-renders - a reader on page 3 is not bounced back to page
 * 1 every refresh. It only moves when the refreshed run list no longer has that many
 * pages (renderTable clamps it to the new last page).
 *
 * There is no isAvailable() - the tab always shows. peekaboot.lifecycle.enabled=false
 * removes the endpoint outright, and an operator who set that flag will not be
 * surprised; failedFetch below renders an honest "unavailable" line instead.
 */
import {badge, emptyState, table} from '../../shared/components.js';
import {formatDateTime, formatLongDuration} from '../../shared/format.js';
import {reconcileFilterWithUrl} from '../../shared/url-filter.js';

export const id = 'lifecycle';
export const label = 'Lifecycle';

const PAGE_SIZE = 20;
const COLUMNS = ['Started', 'Ran for', 'Stopped', 'Down before', 'Build'];

let currentContainer = null;
let currentContext = null;
let runs = null;        // most recent /api/lifecycle/runs response's `runs`, or null before the first load
let fetchFailed = false;
let currentPage = 0;    // 0-indexed, survives across render() calls - see doc comment above

export function render(container, data, context) {
    currentContainer = container;
    currentContext = context;
    if (context.active) reconcileWithUrl(context);
    fetchAndRender();
}

/**
 * The URL's 1-based page param as this module's 0-based page index; anything
 * unparseable, fractional or below 1 is page one. Exported for the browser tests.
 */
export function pageFromUrl(params) {
    const page = Number(params?.page);
    return Number.isInteger(page) && page >= 1 ? page - 1 : 0;
}

/**
 * Reconciles the pager's page with the URL - the same two-direction rule every filter
 * tab applies (see shared/url-filter.js's doc comment), with page one as the default
 * that stays out of the URL. The seeded page may still overshoot the run list (a stale
 * link) - renderTable's clamp corrects both the page and the URL once the list is known.
 */
function reconcileWithUrl(context) {
    reconcileFilterWithUrl(context, ['page'], {
        seed: params => {
            currentPage = pageFromUrl(params);
        },
        hasNonDefaultState: () => currentPage !== 0,
        writeBack: writePageParam
    });
}

/**
 * Writes the current page to the URL (1-based, matching the readout), omitting it on
 * page one so the default yields a clean "#lifecycle" hash. A replace, never a push.
 */
function writePageParam() {
    currentContext.setUrlParams(currentPage === 0 ? {} : {page: String(currentPage + 1)});
}

async function fetchAndRender() {
    const container = currentContainer;
    const context = currentContext;
    if (!context.active) return;

    let result;
    try {
        result = await context.client.get('/api/lifecycle/runs');
    } catch (error) {
        // peekaboot.lifecycle.enabled=false removes the endpoint entirely - that is an
        // expected, operator-chosen state, not a bug, so this logs quietly and renders
        // an honest sentence rather than throwing.
        console.warn('Lifecycle history unavailable:', error);
        fetchFailed = true;
        runs = null;
        renderTable(container, context);
        return;
    }
    if (result === null) return; // superseded by a newer request

    fetchFailed = false;
    runs = result.runs || [];
    renderTable(container, context);
}

function renderTable(container, context) {
    const target = container.querySelector('#lifecycle-runs');
    target.innerHTML = '';

    if (fetchFailed) {
        target.appendChild(emptyState('Lifecycle history is unavailable'));
        return;
    }

    if (!runs || runs.length === 0) {
        target.appendChild(emptyState('No runs recorded yet'));
        return;
    }

    const totalPages = Math.max(1, Math.ceil(runs.length / PAGE_SIZE));
    // Clamp rather than reset - a shrinking list (unlikely, but the log is a ring
    // buffer) must not maroon the reader past the new last page; a growing one must
    // not move them off the page they were reading.
    const clamped = Math.min(Math.max(currentPage, 0), totalPages - 1);
    if (clamped !== currentPage) {
        currentPage = clamped;
        // the URL must name the page actually shown, not the out-of-range one it asked for
        writePageParam();
    }

    const start = currentPage * PAGE_SIZE;
    const {locale, timeZone} = context;

    const rows = runs.slice(start, start + PAGE_SIZE).map(run => renderRow(run, {locale, timeZone}));
    target.appendChild(table(COLUMNS, rows, {className: 'pk-lifecycle-table'}));
    // Rendered whenever there is at least one run, even for a single page, so the
    // control is discoverable and its presence is stable to test.
    target.appendChild(renderPager(totalPages));
}

function renderRow(run, dateOptions) {
    const row = document.createElement('tr');
    if (run.running) row.classList.add('pk-lifecycle-row--running');
    if (run.uncleanExit) row.classList.add('pk-lifecycle-row--unclean');

    row.append(
        startedCell(run, dateOptions),
        ranForCell(run),
        stoppedCell(run, dateOptions),
        downBeforeCell(run),
        buildCell(run, dateOptions)
    );
    return row;
}

function startedCell(run, dateOptions) {
    const td = document.createElement('td');
    td.className = 'pk-table__shrink';
    td.append(document.createTextNode(formatDateTime(run.startedAtEpochMs, dateOptions)));
    if (run.running) {
        td.append(' ');
        td.appendChild(badge('Running', 'ok'));
    }
    return td;
}

function ranForCell(run) {
    const td = document.createElement('td');
    td.className = 'pk-table__shrink';
    // null means the run ended without a matching stop - a crash or a kill - so we
    // genuinely do not know how long it ran. A dash, never a computed guess.
    if (run.ranForMs == null) {
        td.textContent = '-';
        return td;
    }
    td.append(document.createTextNode(formatLongDuration(run.ranForMs)));
    if (run.running) {
        // The server sent elapsed-so-far, not a final duration - say so, rather than
        // let it read like the run is already over.
        td.append(' ');
        td.appendChild(badge('still counting', 'muted'));
    }
    return td;
}

function stoppedCell(run, dateOptions) {
    const td = document.createElement('td');
    td.className = 'pk-table__shrink';
    if (run.stoppedAtEpochMs != null) {
        td.textContent = formatDateTime(run.stoppedAtEpochMs, dateOptions);
        return td;
    }
    // No stop recorded - either still running (no badge, it is not an error), or it
    // died uncleanly (kill -9, crash, power loss - flagged so it reads as a fact, not
    // as missing data).
    td.append(document.createTextNode('-'));
    if (run.uncleanExit) {
        td.append(' ');
        td.appendChild(badge('Unclean exit', 'error'));
    }
    return td;
}

function downBeforeCell(run) {
    const td = document.createElement('td');
    td.className = 'pk-table__shrink';
    // null means unknowable - either nothing precedes this run, or the previous run
    // itself ended uncleanly and left no stop to measure from. Never render that as 0.
    td.textContent = run.downForMs != null ? formatLongDuration(run.downForMs) : '-';
    return td;
}

function buildCell(run, dateOptions) {
    const td = document.createElement('td');
    td.className = 'pk-lifecycle-row__build';
    // The build time is not shown as its own line (the row is already dense) - it is
    // available on hover instead, same as flyway.js's truncated-script tooltip.
    if (run.buildTimeEpochMs != null) {
        td.title = `Built ${formatDateTime(run.buildTimeEpochMs, dateOptions)}`;
    }

    const version = document.createElement('div');
    version.className = 'pk-lifecycle-row__version';
    version.textContent = run.version || '-';
    td.appendChild(version);

    if (run.branch || run.shortCommitId) {
        const meta = document.createElement('div');
        meta.className = 'pk-lifecycle-row__meta';
        meta.textContent = [run.branch, run.shortCommitId].filter(Boolean).join(' @ ');
        td.appendChild(meta);
    }

    if (run.changed && run.changed.length > 0) {
        // changed names exactly what differs from the previous run (version/branch/
        // commit, in that order) - the badge repeats it verbatim rather than just
        // saying "changed".
        td.appendChild(badge(`Deployment: ${run.changed.join(', ')}`, 'info'));
    }

    return td;
}

function renderPager(totalPages) {
    const pager = document.createElement('div');
    pager.className = 'pk-lifecycle-pager';

    const prevBtn = document.createElement('button');
    prevBtn.type = 'button';
    prevBtn.className = 'pk-btn pk-btn--small';
    prevBtn.textContent = 'Previous';
    prevBtn.disabled = currentPage === 0;
    prevBtn.addEventListener('click', () => {
        currentPage -= 1;
        writePageParam();
        renderTable(currentContainer, currentContext);
    });

    const readout = document.createElement('span');
    readout.className = 'pk-lifecycle-pager__readout';
    readout.textContent = `Page ${currentPage + 1} of ${totalPages}`;

    const nextBtn = document.createElement('button');
    nextBtn.type = 'button';
    nextBtn.className = 'pk-btn pk-btn--small';
    nextBtn.textContent = 'Next';
    nextBtn.disabled = currentPage >= totalPages - 1;
    nextBtn.addEventListener('click', () => {
        currentPage += 1;
        writePageParam();
        renderTable(currentContainer, currentContext);
    });

    pager.append(prevBtn, readout, nextBtn);
    return pager;
}
