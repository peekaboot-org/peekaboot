/**
 * Trace-detail overlay - Request tab: overview / request-headers / response-headers
 * sub-tabs. All three build the same `<table class="pk-table">` shape; renderTable/
 * tableRow collapse that duplication into one helper each.
 */
import {escapeHtml} from '../../shared/markup.js';

function renderTable(rows) {
    return `<table class="pk-table">${rows.join('')}</table>`;
}

function tableRow(key, value, valueClass) {
    return `<tr><td>${escapeHtml(key)}</td><td${valueClass ? ` class="${valueClass}"` : ''}>${escapeHtml(value)}</td></tr>`;
}

export function render(container, trace) {
    const httpExchange = trace.httpExchange;
    const req = httpExchange?.request;
    const res = httpExchange?.response;

    // Build sub-tab navigation - reuses the shared .pk-tabs/.pk-tab primitive, scoped
    // to margin-bottom by trace-detail.css's ".pk-overlay__content .pk-tabs" rule.
    let html = '<div class="pk-tabs" role="tablist">';
    html += '<button type="button" class="pk-tab" role="tab" data-subtab="overview" aria-selected="true">Overview</button>';
    html += '<button type="button" class="pk-tab" role="tab" data-subtab="request-headers" aria-selected="false">Request Headers</button>';
    html += '<button type="button" class="pk-tab" role="tab" data-subtab="response-headers" aria-selected="false">Response Headers</button>';
    html += '</div>';
    html += '<div id="pk-request-subtab-content"></div>';

    container.innerHTML = html;

    // Add sub-tab click handlers
    const subtabs = container.querySelectorAll('.pk-tab');
    const subtabContent = container.querySelector('#pk-request-subtab-content');

    subtabs.forEach(tab => {
        tab.addEventListener('click', () => {
            subtabs.forEach(t => t.setAttribute('aria-selected', 'false'));
            tab.setAttribute('aria-selected', 'true');
            renderRequestSubtab(subtabContent, tab.dataset.subtab, req, res, trace);
        });
    });

    // Render default sub-tab
    renderRequestSubtab(subtabContent, 'overview', req, res, trace);
}

function renderRequestSubtab(container, subtab, req, res, trace) {
    switch (subtab) {
        case 'overview': renderRequestOverview(container, req, res, trace); break;
        case 'request-headers': renderRequestHeaders(container, req); break;
        case 'response-headers': renderResponseHeaders(container, res); break;
    }
}

function renderRequestOverview(container, req, res, trace) {
    let html = '';

    // Request info table
    const contentType = req?.headers?.['content-type'] || req?.headers?.['Content-Type'] || '-';
    const requestRows = [
        tableRow('Method', req?.method || '-'),
        tableRow('Path', req?.path || '-'),
        ...(req?.query ? [tableRow('Query String', req.query)] : []),
        tableRow('Status', String(res?.status || '-')),
        tableRow('Content-Type', contentType),
        tableRow('Duration', (trace.durationMs || '-') + 'ms')
    ];
    html += '<div class="pk-request-section">';
    html += '<h3>Request</h3>';
    html += renderTable(requestRows);
    html += '</div>';

    // Controller info (API returns 'class' and 'method' fields)
    if (req?.controller?.class || req?.controller?.method) {
        html += '<div class="pk-request-section">';
        html += '<h3>Controller</h3>';
        html += `<div class="pk-controller-info">${escapeHtml(req.controller.class || 'Unknown')}.${escapeHtml(req.controller.method || 'unknown')}()</div>`;
        html += '</div>';
    }

    // Query Parameters
    const queryParams = req?.params?.query || {};
    if (Object.keys(queryParams).length > 0) {
        html += '<div class="pk-request-section">';
        html += '<h3>Query Parameters</h3>';
        html += renderTable(Object.entries(queryParams).sort().map(([k, v]) =>
            tableRow(k, Array.isArray(v) ? v.join(', ') : v)));
        html += '</div>';
    }

    // Form Parameters
    const formParams = req?.params?.form || {};
    if (Object.keys(formParams).length > 0) {
        html += '<div class="pk-request-section">';
        html += '<h3>Form Parameters</h3>';
        html += renderTable(Object.entries(formParams).sort().map(([k, v]) =>
            tableRow(k, Array.isArray(v) ? v.join(', ') : v)));
        html += '</div>';
    }

    // Uploaded Files
    const files = req?.params?.upload || [];
    if (files.length > 0) {
        html += '<div class="pk-request-section">';
        html += '<h3>Uploaded Files</h3>';
        html += renderTable(files.map(file => {
            const filename = file.originalFilename || file.name || 'unknown';
            return tableRow(filename, `${file.contentType || '-'} (${String(file.size || 0)} bytes)`);
        }));
        html += '</div>';
    }

    // Request Body
    if (req?.body?.content) {
        html += '<div class="pk-request-section">';
        html += '<h3>Request Body' + (req.body.truncated ? ' <span class="pk-request-masked">(truncated)</span>' : '') + '</h3>';
        html += `<div class="pk-query__sql">${escapeHtml(req.body.content)}</div>`;
        html += '</div>';
    }

    container.innerHTML = html || '<div class="pk-empty">No request details available</div>';
}

function renderRequestHeaders(container, req) {
    const reqHeaders = req?.headers || {};
    const rows = Object.keys(reqHeaders).length > 0
        ? Object.entries(reqHeaders).sort().map(([k, v]) => tableRow(k, v, v === '********' ? 'pk-request-masked' : ''))
        : ['<tr><td colspan="2" class="pk-request-masked">No headers captured</td></tr>'];

    container.innerHTML = '<div class="pk-request-section"><h3>Request Headers</h3>' + renderTable(rows) + '</div>';
}

function renderResponseHeaders(container, res) {
    const resHeaders = res?.headers || {};
    const rows = Object.keys(resHeaders).length > 0
        ? Object.entries(resHeaders).sort().map(([k, v]) => tableRow(k, v))
        : ['<tr><td colspan="2" class="pk-request-masked">No headers captured</td></tr>'];

    container.innerHTML = '<div class="pk-request-section"><h3>Response Headers</h3>' + renderTable(rows) + '</div>';
}
