/**
 * The "Flyway" tab: one table row per migration, in the order the backend returns them.
 *
 * A real schema history runs to dozens or hundreds of migrations, so each one gets a
 * single scannable row rather than a card, which would spend roughly 130px per
 * migration on three stacked lines.
 */
import {badge, table} from '../../shared/components.js';
import {formatDurationMs, formatDateTime} from '../../shared/format.js';
import {durationSeverity} from '../../shared/severity.js';

export const id = 'flyway';
export const label = 'Flyway';

const COLUMNS = ['Version', 'Description', 'Script', 'Type', 'Duration', 'Installed', 'Status'];

export function isAvailable(data) {
    return Boolean(data?.flyway?.migrations?.length);
}

/** Every MigrationState the backend collapses Flyway's states onto, and the badge tier each gets. */
const MIGRATION_STATE_VARIANTS = Object.freeze({
    SUCCESS: 'ok', PENDING: 'muted', FAILED: 'error', IGNORED: 'muted', UNKNOWN: 'muted'
});

export const MIGRATION_STATES = Object.keys(MIGRATION_STATE_VARIANTS);

export function render(container, data, {locale, timeZone, features} = {}) {
    const migrations = data?.flyway?.migrations || [];
    const target = container.querySelector('#flyway-timeline');
    target.innerHTML = '';

    if (migrations.length === 0) {
        return;
    }

    const rows = migrations.map(migration => renderRow(migration, {locale, timeZone, features}));
    target.appendChild(table(COLUMNS, rows, {className: 'pk-flyway-table'}));
}

function renderRow(migration, {locale, timeZone, features}) {
    const row = document.createElement('tr');
    if (migration.state === 'FAILED') row.classList.add('pk-flyway-row--failed');
    if (migration.state === 'PENDING') row.classList.add('pk-flyway-row--pending');

    row.append(
        cell(`V${migration.version}`, 'pk-flyway-row__version pk-table__shrink'),
        cell(migration.description, 'pk-flyway-row__description'),
        cell(migration.script, 'pk-table__mono pk-flyway-row__script'),
        cell(migration.type, 'pk-table__shrink'),
        durationCell(migration.executionTime, features),
        cell(formatDateTime(migration.installedOn, {locale, timeZone}), 'pk-table__shrink'),
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

function durationCell(executionTime, features) {
    const td = document.createElement('td');
    td.className = 'pk-table__num pk-table__shrink';
    if (executionTime == null) {
        td.textContent = '';
        return td;
    }
    const severity = durationSeverity(executionTime, features);
    if (severity) td.classList.add(`pk-flyway-row__time--${severity}`);
    td.textContent = formatDurationMs(executionTime);
    return td;
}

function statusCell(state) {
    const td = document.createElement('td');
    td.className = 'pk-table__shrink';
    td.appendChild(badge(state, MIGRATION_STATE_VARIANTS[state] || 'muted'));
    return td;
}
