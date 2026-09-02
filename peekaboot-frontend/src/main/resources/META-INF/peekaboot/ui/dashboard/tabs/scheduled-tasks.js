/**
 * The "Scheduled Tasks" tab: @Scheduled methods grouped by schedule type (cron, fixed
 * delay, fixed rate), each expandable to its individual task rows, with a summary badge
 * row above the groups and a link to the Traces tab for scheduler-triggered traces.
 */
import {groupList, expandedKeys, badge, emptyState} from '../../shared/components.js';
import {formatCount, formatDateTime, formatInterval} from '../../shared/format.js';

export const id = 'scheduled-tasks';
export const label = 'Scheduled Tasks';

let currentData = null;

export function isAvailable(data) {
    return Boolean(data?.scheduledTasks?.tasks?.length);
}

export function render(container, data, context) {
    currentData = data;
    renderGroups(container, context);
}

/** Every TaskType the backend emits, in the order the groups render. */
const TYPE_LABELS = {CRON: 'Cron Tasks', FIXED_DELAY: 'Fixed Delay Tasks', FIXED_RATE: 'Fixed Rate Tasks'};
const TYPE_PILL_LABELS = {CRON: 'Cron', FIXED_DELAY: 'Fixed Delay', FIXED_RATE: 'Fixed Rate'};

export const TASK_TYPES = Object.keys(TYPE_LABELS);

/** SUCCESS -> ok, FAILED -> error, everything else (PENDING/RUNNING/UNKNOWN/unset) -> muted. */
function taskSeverity(status) {
    if (status === 'SUCCESS') return 'ok';
    if (status === 'FAILED') return 'error';
    return 'muted';
}

function renderGroups(container, context) {
    const scheduledTasks = currentData?.scheduledTasks;
    const target = container.querySelector('#scheduled-tasks-groups');
    // Must run before the container is cleared below - see filtered-group-tab.js's renderGroups.
    const expanded = expandedKeys(target);
    target.innerHTML = '';

    const tasks = scheduledTasks?.tasks;
    if (!tasks || tasks.length === 0) {
        target.appendChild(emptyState('No scheduled tasks configured'));
        return;
    }

    renderSummary(container, scheduledTasks, tasks.length);

    const groups = TASK_TYPES
        .map(type => ({type, tasks: tasks.filter(t => t.type === type)}))
        .filter(group => group.tasks.length > 0);

    groupList(target, groups, {
        key: group => group.type,
        header: group => ({name: TYPE_LABELS[group.type], count: formatCount(group.tasks.length, 'task')}),
        items: (group, list) => group.tasks.forEach(task =>
            list.appendChild(renderTaskRow(task, group.type, context))),
        expandedKeys: expanded
    });
}

function renderSummary(container, scheduledTasks, total) {
    const summaryEl = container.querySelector('#scheduled-tasks-summary');
    summaryEl.innerHTML = '';
    summaryEl.appendChild(badge(`Total: ${total}`, 'muted'));
    summaryEl.appendChild(badge(`Cron: ${scheduledTasks.cronCount}`, 'muted'));
    summaryEl.appendChild(badge(`Fixed Delay: ${scheduledTasks.fixedDelayCount}`, 'muted'));
    summaryEl.appendChild(badge(`Fixed Rate: ${scheduledTasks.fixedRateCount}`, 'muted'));
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
    right.appendChild(badge(task.lastStatus || 'PENDING', taskSeverity(task.lastStatus)));

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
        targetRow.appendChild(renderTracesLink(context, task));
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
 * Navigates to the Traces tab pre-filtered to this scheduler's own SCHEDULED_JOB
 * traces, via context.navigate's third (payload) argument - routed by main.js to
 * traces.js's applyFilter(), which owns the actual filter state (see its doc comment).
 */
function renderTracesLink(context, task) {
    const link = document.createElement('a');
    link.href = '#';
    link.className = 'pk-task__traces-link';
    // title alone would not become the accessible name here: the emoji textContent is
    // itself real content, so it (its Unicode name) would win instead. aria-label pins
    // the name to the same text title already carries.
    link.title = 'View traces for this scheduler';
    link.setAttribute('aria-label', 'View traces for this scheduler');
    link.textContent = '\u{1F50D}';
    link.addEventListener('click', (e) => {
        e.preventDefault();
        context.navigate('traces', null, {rootActionType: 'SCHEDULED_JOB', rootOperation: task.target});
    });
    return link;
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
