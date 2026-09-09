/**
 * The "Scheduled Tasks" tab: @Scheduled methods grouped by schedule type (cron, fixed
 * delay, fixed rate), each expandable to its individual task rows, with a summary badge
 * row above the groups and a link to the Traces tab for scheduler-triggered traces.
 */
import {badge, iconLink} from '../../shared/components.js';
import {formatCount, formatDateTime, formatInterval} from '../../shared/format.js';
import {filteredGroupTab} from '../../shared/filtered-group-tab.js';
import {taskStatusVariant} from '../../shared/severity.js';
import {buildAppHash} from '../../shared/url-state.js';

export const id = 'scheduled-tasks';

/** Every TaskType the backend emits, in the order the groups render. */
const TYPE_LABELS = {CRON: 'Cron Tasks', FIXED_DELAY: 'Fixed Delay Tasks', FIXED_RATE: 'Fixed Rate Tasks'};
const TYPE_PILL_LABELS = {CRON: 'Cron', FIXED_DELAY: 'Fixed Delay', FIXED_RATE: 'Fixed Rate'};

export const TASK_TYPES = Object.keys(TYPE_LABELS);

// no inputId: the group shell without a filter (see filtered-group-tab.js)
const tab = filteredGroupTab({
    listId: 'scheduled-tasks-groups',
    select: data => groupsByType(data?.scheduledTasks?.tasks),
    filterGroup: group => group,
    key: group => group.type,
    header: group => ({name: TYPE_LABELS[group.type], count: formatCount(group.tasks.length, 'task')}),
    items: (group, list, query, context) => group.tasks.forEach(task =>
        list.appendChild(renderTaskRow(task, group.type, context))),
    extraTop: data => renderSummary(data.scheduledTasks),
    emptyMessage: 'No scheduled tasks configured'
});

export function isAvailable(data) {
    return Boolean(data?.scheduledTasks?.tasks?.length);
}

export function render(container, data, context) {
    tab.render(container, data, context);
}

function groupsByType(tasks) {
    if (!tasks) return [];
    return TASK_TYPES
        .map(type => ({type, tasks: tasks.filter(task => task.type === type)}))
        .filter(group => group.tasks.length > 0);
}

function renderSummary(scheduledTasks) {
    const summaryEl = document.createElement('div');
    summaryEl.className = 'pk-tasks-summary';
    summaryEl.appendChild(badge(`Total: ${scheduledTasks.tasks.length}`, 'muted'));
    summaryEl.appendChild(badge(`Cron: ${scheduledTasks.cronCount}`, 'muted'));
    summaryEl.appendChild(badge(`Fixed Delay: ${scheduledTasks.fixedDelayCount}`, 'muted'));
    summaryEl.appendChild(badge(`Fixed Rate: ${scheduledTasks.fixedRateCount}`, 'muted'));
    return summaryEl;
}

function renderTaskRow(task, type, context) {
    const {locale, timeZone} = context;
    const dateOptions = {locale, timeZone};

    const item = document.createElement('div');
    item.className = 'pk-task';

    const targetShort = task.target.includes('.')
        ? task.target.split('.').slice(-2).join('.')
        : task.target;
    const scheduleDisplay = type === 'CRON'
        ? (task.scheduleDescription || task.schedule)
        : formatFixedInterval(task.intervalMs);

    const row = document.createElement('div');
    row.className = 'pk-task__row';

    const left = document.createElement('div');
    left.className = 'pk-task__left';
    left.appendChild(badge(TYPE_PILL_LABELS[type], 'info'));

    const scheduleEl = document.createElement('span');
    scheduleEl.className = 'pk-task__schedule';
    // the description truncates when the row is narrow, so the tooltip has to carry it
    // as well as the raw expression it was derived from - when the backend shipped one;
    // a fixed-interval task may carry no schedule string at all
    scheduleEl.title = task.schedule && scheduleDisplay !== task.schedule
        ? `${scheduleDisplay} (${task.schedule})`
        : scheduleDisplay;
    scheduleEl.textContent = scheduleDisplay;
    left.appendChild(scheduleEl);

    const right = document.createElement('div');
    right.className = 'pk-task__right';
    right.appendChild(timingEl('Last:', task.lastExecution ? formatDateTime(task.lastExecution, dateOptions) : 'Never'));
    right.appendChild(timingEl('Next:', task.nextExecution ? formatDateTime(task.nextExecution, dateOptions) : '-'));
    right.appendChild(badge(task.lastStatus || 'PENDING', taskStatusVariant(task.lastStatus)));

    row.append(left, right);
    item.appendChild(row);

    const targetRow = document.createElement('div');
    targetRow.className = 'pk-task__target-row';

    const targetEl = document.createElement('span');
    targetEl.className = 'pk-task__target';
    targetEl.title = task.target;
    targetEl.textContent = targetShort;
    targetRow.appendChild(targetEl);

    if (context.features?.tracing) {
        targetRow.appendChild(renderTracesLink(task));
    }

    item.appendChild(targetRow);

    if (task.lastException) {
        item.appendChild(renderException(task.lastException));
    }

    return item;
}

function timingEl(labelText, value) {
    const el = document.createElement('span');
    el.className = 'pk-task__timing';
    const labelEl = document.createElement('span');
    labelEl.className = 'pk-task__timing-label';
    labelEl.textContent = labelText;
    el.append(labelEl, document.createTextNode(' ' + value));
    return el;
}

/**
 * A plain deep link into the Traces tab, pre-filtered to this scheduler's own
 * SCHEDULED_JOB traces: the same "#traces?type=...&op=..." a shared link carries, restored
 * by traces.js's own URL reconciliation once the hash router lands there.
 */
function renderTracesLink(task) {
    return iconLink(buildAppHash({tab: 'traces', params: {type: 'SCHEDULED_JOB', op: task.target}}), {
        label: 'View traces for this scheduler',
        icon: '\u{1F50D}',
        className: 'pk-task__traces-link'
    });
}

function renderException(lastException) {
    const el = document.createElement('div');
    el.className = 'pk-task__exception';
    const labelEl = document.createElement('span');
    labelEl.className = 'pk-task__exception-label';
    labelEl.textContent = 'Error during last Execution:';
    el.append(labelEl, document.createTextNode(' ' + lastException));
    return el;
}

function formatFixedInterval(ms) {
    return ms ? `Every ${formatInterval(ms)}` : '-';
}
