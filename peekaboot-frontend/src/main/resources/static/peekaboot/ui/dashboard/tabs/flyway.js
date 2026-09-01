/**
 * The "Flyway" tab: one table row per migration, in the order the backend returns them.
 *
 * A real schema history runs to dozens or hundreds of migrations, so each one gets a
 * single scannable row rather than a card, which would spend roughly 130px per
 * migration on three stacked lines.
 */
import {badge} from '../../shared/components.js';
import {formatDurationMs, formatDateTime} from '../../shared/format.js';
import {durationSeverity} from '../../shared/severity.js';

export const id = 'flyway';
export const label = 'Flyway';

const COLUMNS = ['Version', 'Description', 'Script', 'Type', 'Duration', 'Installed', 'Status'];

export function isAvailable(data) {
    return Boolean(data?.flyway?.migrations?.length);
}

export function render(container, data, {locale, timeZone} = {}) {
    const migrations = data?.flyway?.migrations || [];
    const target = container.querySelector('#flyway-timeline');
    target.innerHTML = '';

    if (migrations.length === 0) {
        return;
    }

    const scroll = document.createElement('div');
    scroll.className = 'pk-table-scroll';

    const table = document.createElement('table');
    table.className = 'pk-table pk-flyway-table';
    table.append(renderHead(), renderBody(migrations, {locale, timeZone}));

    scroll.appendChild(table);
    target.appendChild(scroll);
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

function renderBody(migrations, dateOptions) {
    const body = document.createElement('tbody');
    migrations.forEach(migration => body.appendChild(renderRow(migration, dateOptions)));
    return body;
}

function statusVariant(state) {
    if (state === 'SUCCESS') return 'ok';
    if (state === 'FAILED') return 'error';
    return 'muted';
}

function renderRow(migration, dateOptions) {
    const row = document.createElement('tr');
    if (migration.state === 'FAILED') row.classList.add('pk-flyway-row--failed');
    if (migration.state === 'PENDING') row.classList.add('pk-flyway-row--pending');

    row.append(
        cell(`V${migration.version}`, 'pk-flyway-row__version pk-table__shrink'),
        cell(migration.description, 'pk-flyway-row__description'),
        cell(migration.script, 'pk-table__mono pk-flyway-row__script'),
        cell(migration.type, 'pk-table__shrink'),
        durationCell(migration.executionTime),
        cell(formatDateTime(migration.installedOn, dateOptions), 'pk-table__shrink'),
        statusCell(migration.state)
    );
    return row;
}

function cell(text, className) {
    const td = document.createElement('td');
    if (className) td.className = className;
    td.textContent = text ?? '';
    // the script column is the one that can genuinely overflow; a title keeps the
    // full value reachable once the cell truncates
    if (text) td.title = String(text);
    return td;
}

function durationCell(executionTime) {
    const td = document.createElement('td');
    td.className = 'pk-table__num pk-table__shrink';
    if (executionTime == null) {
        td.textContent = '';
        return td;
    }
    const severity = durationSeverity(executionTime);
    if (severity) td.classList.add(`pk-flyway-row__time--${severity}`);
    td.textContent = formatDurationMs(executionTime);
    return td;
}

function statusCell(state) {
    const td = document.createElement('td');
    td.className = 'pk-table__shrink';
    td.appendChild(badge(state, statusVariant(state)));
    return td;
}
