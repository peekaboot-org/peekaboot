/**
 * The "Lifecycle" tab: the application's start/stop history turned into runs, one row
 * per run, newest first (the server already orders /api/lifecycle/runs that way).
 * Paired with Insights in main.js's TABS order - both are views of the application over
 * time, Insights on what it measured, this on when it existed.
 *
 * render() is called on every 30s auto-refresh cycle for every available tab regardless
 * of which is visible (see main.js's renderData()), so the actual network fetch is
 * skipped here unless this tab's container is the active one - same guard meters.js
 * uses, and for the same reason (main.js's renderTabById() calls render() again the
 * moment this tab becomes active, so switching to it never waits on the next cycle).
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
import {badge} from '../../shared/components.js';
import {formatDateTime, formatLongDuration} from '../../shared/format.js';

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
    fetchAndRender();
}

async function fetchAndRender() {
    const container = currentContainer;
    const context = currentContext;
    // Not the active tab - skip the round trip. main.js's renderTabById() calls
    // render() (and so this) again the instant this tab is switched to.
    if (!container.classList.contains('active')) return;

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
        target.innerHTML = '<p class="pk-empty">Lifecycle history is unavailable</p>';
        return;
    }

    if (!runs || runs.length === 0) {
        target.innerHTML = '<p class="pk-empty">No runs recorded yet</p>';
        return;
    }

    const totalPages = Math.max(1, Math.ceil(runs.length / PAGE_SIZE));
    // Clamp rather than reset - a shrinking list (unlikely, but the log is a ring
    // buffer) must not maroon the reader past the new last page; a growing one must
    // not move them off the page they were reading.
    currentPage = Math.min(Math.max(currentPage, 0), totalPages - 1);

    const start = currentPage * PAGE_SIZE;
    const {locale, timeZone} = context;

    const scroll = document.createElement('div');
    scroll.className = 'pk-table-scroll';

    const table = document.createElement('table');
    table.className = 'pk-table pk-lifecycle-table';
    table.append(renderHead(), renderBody(runs.slice(start, start + PAGE_SIZE), {locale, timeZone}));

    scroll.appendChild(table);
    target.appendChild(scroll);
    // Rendered whenever there is at least one run, even for a single page, so the
    // control is discoverable and its presence is stable to test.
    target.appendChild(renderPager(totalPages));
}

function renderHead() {
    const head = document.createElement('thead');
    const row = document.createElement('tr');
    COLUMNS.forEach(label => {
        const th = document.createElement('th');
        th.scope = 'col';
        th.textContent = label;
        row.appendChild(th);
    });
    head.appendChild(row);
    return head;
}

function renderBody(pageRuns, dateOptions) {
    const body = document.createElement('tbody');
    pageRuns.forEach(run => body.appendChild(renderRow(run, dateOptions)));
    return body;
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
        renderTable(currentContainer, currentContext);
    });

    pager.append(prevBtn, readout, nextBtn);
    return pager;
}
