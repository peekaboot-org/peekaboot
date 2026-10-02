/** The "Liquibase" tab: one table row per change set, in the backend's orderExecuted order; Liquibase records no duration. */
import {badge, cell, table, textCell} from '../../shared/components.js';
import {formatDateTime} from '../../shared/format.js';
import {changeSetExecTypeVariant} from '../../shared/severity.js';

export const id = 'liquibase';

const COLUMNS = ['Order', 'ID', 'Author', 'Changelog', 'Description', 'Executed', 'Status'];

export function isAvailable(data) {
    return Boolean(data?.liquibase?.changeSets?.length);
}

export function render(container, data, {locale, timeZone} = {}) {
    const changeSets = data?.liquibase?.changeSets || [];
    const target = container.querySelector('#liquibase-changesets');
    target.innerHTML = '';

    if (changeSets.length === 0) {
        return;
    }

    const rows = changeSets.map(changeSet => renderRow(changeSet, {locale, timeZone}));
    target.appendChild(table(COLUMNS, rows, {className: 'pk-table--card'}));
}

function renderRow(changeSet, {locale, timeZone}) {
    const row = document.createElement('tr');
    if (changeSet.execType === 'FAILED') row.classList.add('pk-table__stripe--danger');

    row.append(
        textCell(changeSet.orderExecuted, 'pk-table__num pk-table__shrink'),
        textCell(changeSet.id, 'pk-liquibase-row__id'),
        textCell(changeSet.author, 'pk-table__shrink'),
        textCell(changeSet.changeLog, 'pk-table__mono pk-liquibase-row__changelog'),
        textCell(changeSet.description, 'pk-liquibase-row__description'),
        textCell(formatDateTime(changeSet.dateExecuted, {locale, timeZone}), 'pk-table__shrink'),
        cell({className: 'pk-table__shrink'}, badge(changeSet.execType, changeSetExecTypeVariant(changeSet.execType)))
    );
    return row;
}
