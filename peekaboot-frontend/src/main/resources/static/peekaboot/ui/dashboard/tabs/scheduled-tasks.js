/**
 * The "Scheduled Tasks" tab: @Scheduled methods grouped by schedule type (cron, fixed
 * delay, fixed rate), each expandable to its individual task rows, with a summary badge
 * row above the groups and a link to the Traces tab for scheduler-triggered traces.
 */
import {groupList, expandedKeys, badge} from '../../shared/components.js';
import {formatDateTime} from '../../shared/format.js';

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

const TYPE_LABELS = {CRON: 'Cron Tasks', FIXED_DELAY: 'Fixed Delay Tasks', FIXED_RATE: 'Fixed Rate Tasks'};
const TYPE_PILL_LABELS = {CRON: 'Cron', FIXED_DELAY: 'Fixed Delay', FIXED_RATE: 'Fixed Rate'};

/** SUCCESS -> ok, FAILED/ERROR -> error, everything else (PENDING/RUNNING/unset) -> muted. */
function taskSeverity(status) {
    if (status === 'SUCCESS') return 'ok';
    if (status === 'FAILED' || status === 'ERROR') return 'error';
    return 'muted';
}

function renderGroups(container, context) {
    const scheduledTasks = currentData?.scheduledTasks;
    const target = container.querySelector('#scheduled-tasks-groups');
    // Must run before the container is cleared below - see environment.js.
    const expanded = expandedKeys(target);
    target.innerHTML = '';

    const tasks = scheduledTasks?.tasks;
    if (!tasks || tasks.length === 0) {
        target.innerHTML = '<p class="pk-empty">No scheduled tasks configured</p>';
        return;
    }

    renderSummary(container, scheduledTasks, tasks.length);

    const groups = ['CRON', 'FIXED_DELAY', 'FIXED_RATE']
        .map(type => ({type, tasks: tasks.filter(t => t.type === type)}))
        .filter(group => group.tasks.length > 0);

    groupList(target, groups, {
        key: group => group.type,
        header: group => ({name: TYPE_LABELS[group.type], count: `${group.tasks.length} tasks`}),
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
    scheduleEl.title = task.schedule;
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
        targetRow.appendChild(renderTracesLink(context));
    }

    item.appendChild(targetRow);

    if (task.lastException) {
        item.appendChild(renderException(task.lastException));
    }

    return item;
}

function timingEl(label, value) {
    const el = document.createElement('span');
    el.className = 'pk-task__timing';
    const labelEl = document.createElement('span');
    labelEl.className = 'pk-task__timing-label';
    labelEl.textContent = label;
    el.append(labelEl, document.createTextNode(' ' + value));
    return el;
}

/**
 * Plain "jump to the Traces tab" navigation - the original also pre-applied a
 * rootActionType=SCHEDULED_JOB + target filter, but traces.js owns that filter
 * state privately (see its module doc comment) and exposes no way to set it
 * from outside, so the pre-filtering does not carry over in this migration.
 */
function renderTracesLink(context) {
    const link = document.createElement('a');
    link.href = '#';
    link.className = 'pk-task__traces-link';
    link.title = 'View traces for this scheduler';
    link.textContent = '\u{1F50D}';
    link.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        context.navigate('traces');
    });
    return link;
}

function renderException(lastException) {
    const el = document.createElement('div');
    el.className = 'pk-task__exception';
    const label = document.createElement('span');
    label.className = 'pk-task__exception-label';
    label.textContent = 'Error during last Execution:';
    el.append(label, document.createTextNode(' ' + lastException));
    return el;
}

function formatFixedInterval(ms) {
    if (!ms) return '-';
    if (ms < 1000) return `Every ${ms}ms`;
    if (ms < 60000) return `Every ${ms / 1000}s`;
    if (ms < 3600000) return `Every ${ms / 60000}m`;
    if (ms < 86400000) return `Every ${ms / 3600000}h`;
    return `Every ${ms / 86400000}d`;
}
