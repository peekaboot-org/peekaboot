/**
 * The "Flyway" tab: one card per migration, in the order the backend returns them.
 */
import {badge} from '../../shared/components.js';
import {formatDurationMs, formatDateTime} from '../../shared/format.js';
import {durationSeverity} from '../../shared/severity.js';

export const id = 'flyway';
export const label = 'Flyway';

export function isAvailable(data) {
    return Boolean(data?.flyway?.migrations?.length);
}

export function render(container, data, {locale, timeZone} = {}) {
    const migrations = data?.flyway?.migrations || [];
    const target = container.querySelector('#flyway-timeline');
    target.innerHTML = '';

    migrations.forEach(migration => target.appendChild(renderCard(migration, {locale, timeZone})));
}

function statusVariant(state) {
    if (state === 'SUCCESS') return 'ok';
    if (state === 'FAILED') return 'error';
    return 'muted';
}

function renderCard(migration, dateOptions) {
    const card = document.createElement('div');
    card.className = 'pk-flyway';
    if (migration.state === 'FAILED') card.classList.add('pk-flyway--failed');
    if (migration.state === 'PENDING') card.classList.add('pk-flyway--pending');

    const header = document.createElement('div');
    header.className = 'pk-flyway__header';

    const versionEl = document.createElement('span');
    versionEl.className = 'pk-flyway__version';
    versionEl.textContent = `V${migration.version}`;

    const descriptionEl = document.createElement('span');
    descriptionEl.className = 'pk-flyway__description';
    descriptionEl.textContent = migration.description;

    header.append(versionEl, descriptionEl, badge(migration.state, statusVariant(migration.state)));

    const details = document.createElement('div');
    details.className = 'pk-flyway__details';

    if (migration.executionTime != null) {
        details.appendChild(renderExecutionTime(migration.executionTime));
    }

    const dateEl = document.createElement('span');
    dateEl.className = 'pk-flyway__date';
    dateEl.textContent = formatDateTime(migration.installedOn, dateOptions);
    details.appendChild(dateEl);

    const typeEl = document.createElement('span');
    typeEl.className = 'pk-flyway__type';
    typeEl.textContent = migration.type;
    details.appendChild(typeEl);

    const script = document.createElement('div');
    script.className = 'pk-flyway__script';
    script.textContent = migration.script;

    card.append(header, details, script);
    return card;
}

function renderExecutionTime(executionTime) {
    const severity = durationSeverity(executionTime);
    const timeEl = document.createElement('span');
    timeEl.className = 'pk-flyway__time' + (severity ? ` pk-flyway__time--${severity}` : '');
    timeEl.textContent = `⏱ ${formatDurationMs(executionTime)}`;
    return timeEl;
}
