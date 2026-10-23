/**
 * The "Scheduled Tasks" tab: @Scheduled methods grouped by schedule type (cron, fixed
 * delay, fixed rate), each expandable to its individual task rows, with a summary badge
 * row above the groups and a link to the Traces tab for scheduler-triggered traces.
 */
import {badge, countSummary, iconLink} from '../../shared/components.js';
import {el} from '../../shared/dom.js';
import {formatCount, formatDateTime, formatInterval, shortTarget} from '../../shared/format.js';
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
    header: (group, query, context) => ({
        name: TYPE_LABELS[group.type],
        count: formatCount(group.tasks.length, 'task', {locale: context.locale})
    }),
    items: (group, list, query, context) => group.tasks.forEach(task =>
        list.appendChild(renderTaskRow(task, group.type, context))),
    extraTop: ({scheduledTasks}, context) => countSummary([
        ['Total', scheduledTasks.tasks.length],
        ['Cron', scheduledTasks.cronCount],
        ['Fixed Delay', scheduledTasks.fixedDelayCount],
        ['Fixed Rate', scheduledTasks.fixedRateCount]
    ], context.locale),
    emptyMessage: 'No scheduled tasks configured'
});

export function isAvailable(data) {
    return Boolean(data?.scheduledTasks?.tasks?.length);
}

export const {render} = tab;

function groupsByType(tasks) {
    if (!tasks) return [];
    return TASK_TYPES
        .map(type => ({type, tasks: tasks.filter(task => task.type === type)}))
        .filter(group => group.tasks.length > 0);
}

function renderTaskRow(task, type, context) {
    const {locale, timeZone} = context;
    const dateOptions = {locale, timeZone};

    const scheduleDisplay = type === 'CRON'
        ? (task.scheduleDescription || task.schedule)
        : formatFixedInterval(task.intervalMs);

    // the description truncates when the row is narrow, so the tooltip has to carry it
    // as well as the raw expression it was derived from - when the backend shipped one;
    // a fixed-interval task may carry no schedule string at all
    const scheduleTitle = task.schedule && scheduleDisplay !== task.schedule
        ? `${scheduleDisplay} (${task.schedule})`
        : scheduleDisplay;

    return el('div', {className: 'pk-task'},
        el('div', {className: 'pk-task__row'},
            el('div', {className: 'pk-task__left'},
                badge(TYPE_PILL_LABELS[type], 'info'),
                el('span', {className: 'pk-task__schedule', text: scheduleDisplay, title: scheduleTitle})),
            el('div', {className: 'pk-task__right'},
                timingEl('Last:', task.lastExecution ? formatDateTime(task.lastExecution, dateOptions) : 'Never'),
                timingEl('Next:', task.nextExecution ? formatDateTime(task.nextExecution, dateOptions) : '-'),
                badge(task.lastStatus || 'PENDING', taskStatusVariant(task.lastStatus)))),
        el('div', {className: 'pk-task__target-row'},
            el('span', {className: 'pk-task__target', text: shortTarget(task.target), title: task.target}),
            context.features?.tracing ? renderTracesLink(task) : null),
        task.lastException ? renderException(task.lastException) : null);
}

function timingEl(labelText, value) {
    return el('span', {className: 'pk-task__timing'},
        el('span', {className: 'pk-task__timing-label', text: labelText}), ' ' + value);
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
    return el('div', {className: 'pk-task__exception'},
        el('span', {className: 'pk-task__exception-label', text: 'Error during last Execution:'}), ' ' + lastException);
}

function formatFixedInterval(ms) {
    return ms ? `Every ${formatInterval(ms)}` : '-';
}
