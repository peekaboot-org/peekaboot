/**
 * Trace-detail overlay - Request tab: the whole HTTP exchange on one page, request
 * details first and the two header tables last. Every section builds the same
 * `<table class="pk-table pk-table--kv">` shape; renderTable/tableRow collapse that duplication
 * into one helper each.
 */
import {escapeHtml, MASK_LITERAL} from '../../shared/markup.js';
import {formatDurationMs} from '../../shared/format.js';
import {statusLabel, statusVariant} from '../../shared/http-status.js';

function renderTable(rows) {
    return `<table class="pk-table pk-table--kv">${rows.join('')}</table>`;
}

function tableRowHtml(key, valueHtml, valueClass) {
    return `<tr><td>${escapeHtml(key)}</td><td${valueClass ? ` class="${valueClass}"` : ''}>${valueHtml}</td></tr>`;
}

function tableRow(key, value, valueClass) {
    return tableRowHtml(key, escapeHtml(value), valueClass);
}

function section(title, body) {
    return `<div class="pk-request-section"><h3>${title}</h3>${body}</div>`;
}

export function render(container, trace) {
    const httpExchange = trace.httpExchange;
    const req = httpExchange?.request;
    const res = httpExchange?.response;

    if (!req && !res) {
        container.innerHTML = '<div class="pk-empty">No request details available</div>';
        return;
    }

    container.innerHTML = renderRequestDetails(req, res, trace)
        + renderController(req)
        + renderParams('Query Parameters', req?.params?.query)
        + renderParams('Form Parameters', req?.params?.form)
        + renderUploadedFiles(req?.params?.upload)
        + renderRequestBody(req?.body)
        + renderRequestHeaders(req)
        + renderResponseHeaders(res);
}

function renderRequestDetails(req, res, trace) {
    const contentType = req?.headers?.['content-type'] || req?.headers?.['Content-Type'] || '-';
    const rows = [
        tableRow('Method', req?.method || '-'),
        tableRow('Path', req?.path || '-'),
        ...(req?.query ? [tableRow('Query String', req.query)] : []),
        tableRowHtml('Status', statusBadgeHtml(res?.status)),
        tableRow('Content-Type', contentType),
        tableRow('Duration', formatDurationMs(trace.durationMs))
    ];
    return section('Request', renderTable(rows));
}

function statusBadgeHtml(status) {
    return `<span class="pk-badge pk-badge--${statusVariant(status)}">${escapeHtml(statusLabel(status))}</span>`;
}

function renderController(req) {
    if (!req?.controller?.class && !req?.controller?.method) return '';
    const signature = `${escapeHtml(req.controller.class || 'Unknown')}.${escapeHtml(req.controller.method || 'unknown')}()`;
    return section('Controller', `<div class="pk-controller-info">${signature}</div>`);
}

function renderParams(title, params) {
    const entries = Object.entries(params || {});
    if (entries.length === 0) return '';
    return section(title, renderTable(entries.sort().map(([key, value]) =>
        tableRow(key, Array.isArray(value) ? value.join(', ') : value))));
}

function renderUploadedFiles(files) {
    if (!files?.length) return '';
    return section('Uploaded Files', renderTable(files.map(file =>
        tableRow(file.originalFilename || file.name || 'unknown',
            `${file.contentType || '-'} (${String(file.size || 0)} bytes)`))));
}

function renderRequestBody(body) {
    if (!body?.content) return '';
    const title = 'Request Body' + (body.truncated ? ' <span class="pk-request-masked">(truncated)</span>' : '');
    return section(title, `<div class="pk-query__sql">${escapeHtml(body.content)}</div>`);
}

function renderRequestHeaders(req) {
    const headers = Object.entries(req?.headers || {});
    const rows = headers.length > 0
        ? headers.sort().map(([key, value]) => tableRow(key, value, value === MASK_LITERAL ? 'pk-request-masked' : ''))
        : [noHeadersRow()];
    return section('Request Headers', renderTable(rows));
}

function renderResponseHeaders(res) {
    const headers = Object.entries(res?.headers || {});
    const rows = headers.length > 0
        ? headers.sort().map(([key, value]) => tableRow(key, value))
        : [noHeadersRow()];
    return section('Response Headers', renderTable(rows));
}

/**
 * Both header sections render even when empty: "nothing was captured" is itself worth
 * seeing, and a section that vanishes reads as a missing feature rather than an answer.
 */
function noHeadersRow() {
    return '<tr><td colspan="2" class="pk-request-empty">No headers captured</td></tr>';
}
