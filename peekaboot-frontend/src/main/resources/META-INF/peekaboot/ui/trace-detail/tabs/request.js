/**
 * Trace-detail overlay - Request tab: the whole HTTP exchange on one page, request
 * details first and the two header tables last. Every section is the same
 * `<table class="pk-table pk-table--kv">` shape; kvTable/tableRow build it.
 */
import {MASK_LITERAL} from '../../shared/markup.js';
import {badge, emptyState} from '../../shared/components.js';
import {el} from '../../shared/dom.js';
import {formatDurationMs} from '../../shared/format.js';
import {statusLabel, statusVariant} from '../../shared/http-status.js';

// Code-point order, not localeCompare's: collation is the reader's browser setting, and
// two readers must not see the same trace's headers and parameters in different orders.
const byKey = ([a], [b]) => (a < b ? -1 : a > b ? 1 : 0);

function kvTable(rows) {
    return el('table', {className: 'pk-table pk-table--kv'}, ...rows);
}

/** `value` is text, or an element for a cell that holds a control or a pill. */
function tableRow(key, value, valueClass) {
    const cell = el('td', {className: valueClass || undefined});
    cell.append(value);
    return el('tr', {}, el('td', {text: key}), cell);
}

/** `note` is the muted aside a title can carry ("(truncated)"). */
function section(title, body, note) {
    const heading = el('h3', {className: 'pk-label', text: title});
    if (note) heading.append(' ', el('span', {className: 'pk-note', text: note}));
    return el('div', {className: 'pk-request-section'}, heading, body);
}

export function render(container, trace, view = {}) {
    const maskLiteral = view.features?.maskLiteral ?? MASK_LITERAL;
    const httpExchange = trace.httpExchange;
    const req = httpExchange?.request;
    const res = httpExchange?.response;

    if (!req && !res) {
        container.replaceChildren(emptyState('No request details available'));
        return;
    }

    container.replaceChildren(...[
        renderRequestDetails(req, res, trace),
        renderController(req),
        renderParams('Query Parameters', req?.params?.query),
        renderParams('Form Parameters', req?.params?.form),
        renderUploadedFiles(req?.params?.upload),
        renderRequestBody(req?.body),
        renderHeaders('Request Headers', req?.headers, maskLiteral),
        renderHeaders('Response Headers', res?.headers, maskLiteral)
    ].filter(Boolean));
}

/** Header names are stored as the container spelled them, so the lookup ignores case. */
function headerValue(headers, name) {
    return Object.entries(headers || {}).find(([key]) => key.toLowerCase() === name)?.[1];
}

function renderRequestDetails(req, res, trace) {
    const rows = [
        tableRow('Method', req?.method || '-'),
        tableRow('Path', req?.path || '-'),
        ...(req?.query ? [tableRow('Query String', req.query)] : []),
        tableRow('Status', badge(statusLabel(res?.status), statusVariant(res?.status))),
        tableRow('Content-Type', headerValue(req?.headers, 'content-type') || '-'),
        tableRow('Duration', formatDurationMs(trace.durationMs))
    ];
    return section('Request', kvTable(rows));
}

function renderController(req) {
    if (!req?.controller?.class && !req?.controller?.method) return null;
    const signature = `${req.controller.class || 'Unknown'}.${req.controller.method || 'unknown'}()`;
    return section('Controller', el('div', {className: 'pk-controller-info', text: signature}));
}

function renderParams(title, params) {
    const entries = Object.entries(params || {});
    if (entries.length === 0) return null;
    return section(title, kvTable(entries.sort(byKey).map(([key, value]) =>
        tableRow(key, Array.isArray(value) ? value.join(', ') : String(value)))));
}

function renderUploadedFiles(files) {
    if (!files?.length) return null;
    return section('Uploaded Files', kvTable(files.map(file =>
        tableRow(file.originalFilename || file.name || 'unknown',
            `${file.contentType || '-'} (${String(file.size || 0)} bytes)`))));
}

function renderRequestBody(body) {
    if (!body?.content) return null;
    return section('Request Body', el('div', {className: 'pk-code-block', text: body.content}),
        body.truncated ? '(truncated)' : null);
}

/**
 * Both header sections render even when empty: "nothing was captured" is itself worth
 * seeing, and a section that vanishes reads as a missing feature rather than an answer.
 * A value the masking engine replaced is marked, so the mask tests can count them.
 */
function renderHeaders(title, headers, maskLiteral) {
    const entries = Object.entries(headers || {});
    const rows = entries.length > 0
        ? entries.sort(byKey).map(([key, value]) => tableRow(key, value, value === maskLiteral ? 'pk-note pk-request-masked' : ''))
        : [el('tr', {}, el('td', {className: 'pk-note pk-request-empty', text: 'No headers captured', attrs: {colspan: '2'}}))];
    return section(title, kvTable(rows));
}
