/**
 * The "Flyway" tab: one table row per migration, in the order the backend returns them.
 *
 * A real schema history runs to dozens or hundreds of migrations, so each one gets a
 * single scannable row rather than a card, which would spend roughly 130px per
 * migration on three stacked lines.
 */
import {badge, cell, table} from '../../shared/components.js';
import {formatDurationMs, formatDateTime} from '../../shared/format.js';
import {migrationStateVariant} from '../../shared/severity.js';

export const id = 'flyway';

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

    const rows = migrations.map(migration => renderRow(migration, {locale, timeZone}));
    target.appendChild(table(COLUMNS, rows, {className: 'pk-table--card'}));
}

function renderRow(migration, {locale, timeZone}) {
    const row = document.createElement('tr');
    if (migration.state === 'FAILED') row.classList.add('pk-table__stripe--danger');
    if (migration.state === 'PENDING') row.classList.add('pk-table__stripe--muted');

    row.append(
        textCell(`V${migration.version}`, 'pk-flyway-row__version pk-table__shrink'),
        textCell(migration.description, 'pk-flyway-row__description'),
        textCell(migration.script, 'pk-table__mono pk-flyway-row__script'),
        textCell(migration.type, 'pk-table__shrink'),
        durationCell(migration.executionTime),
        textCell(formatDateTime(migration.installedOn, {locale, timeZone}), 'pk-table__shrink'),
        cell({className: 'pk-table__shrink'}, badge(migration.state, migrationStateVariant(migration.state)))
    );
    return row;
}

/** The script column is the one that can genuinely overflow; the title keeps the full value reachable once it truncates. */
function textCell(text, className) {
    return cell({className, title: text ? String(text) : undefined}, text ?? '');
}

/**
 * Uncoloured on purpose: the span thresholds describe request spans, and a migration
 * that takes seconds is doing its job, not misbehaving.
 */
function durationCell(executionTime) {
    return cell({className: 'pk-table__num pk-table__shrink'}, executionTime == null ? '' : formatDurationMs(executionTime));
}
