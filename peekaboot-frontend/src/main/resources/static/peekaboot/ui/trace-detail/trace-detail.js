(function(global) {
    'use strict';

    // Fallback utilities when PeekabootUtils is not loaded (e.g., toolbar context)
    const Utils = (typeof PeekabootUtils !== 'undefined') ? PeekabootUtils : {
        escapeHtml: function(text) {
            if (text == null) return '';
            return String(text).replace(/[&<>"']/g, c =>
                ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
        },
        getDurationClass: function(ms) {
            if (ms > 500) return 'very-slow';
            if (ms > 100) return 'slow';
            return '';
        }
    };

    const STYLES = `
        :host {
            --pk-bg: #0d1117;
            --pk-bg-alt: #161b22;
            --pk-bg-hover: #21262d;
            --pk-border: #30363d;
            --pk-text: #c9d1d9;
            --pk-text-muted: #8b949e;
            --pk-text-strong: #f0f6fc;
            --pk-primary: #58a6ff;
            --pk-success: #3fb950;
            --pk-warning: #d29922;
            --pk-danger: #f85149;
            --pk-purple: #a371f7;
            --pk-font: system-ui, -apple-system, sans-serif;
            --pk-font-mono: ui-monospace, SFMono-Regular, monospace;
            --pk-radius: 6px;
        }

        .pk-trace-overlay {
            position: fixed;
            inset: 0;
            background: var(--pk-bg);
            z-index: 2147483647;
            display: flex;
            flex-direction: column;
        }

        .pk-trace-container {
            width: 100vw;
            height: 100vh;
            background: var(--pk-bg);
            display: flex;
            flex-direction: column;
            overflow: hidden;
            color: var(--pk-text);
            font: 13px/1.5 var(--pk-font);
            position: relative;
        }

        .pk-trace-header {
            padding: 16px 20px;
            background: var(--pk-bg-alt);
            border-bottom: 1px solid var(--pk-border);
        }

        .pk-trace-back {
            position: absolute;
            top: 12px;
            left: 16px;
            background: transparent;
            border: none;
            color: var(--pk-text-muted);
            font-size: 20px;
            cursor: pointer;
            padding: 4px 8px;
            line-height: 1;
        }
        .pk-trace-back:hover { color: var(--pk-primary); }

        .pk-trace-title {
            display: flex;
            align-items: center;
            gap: 12px;
            margin-bottom: 8px;
            margin-left: 32px;
        }

        .pk-trace-title-icon { font-size: 24px; }
        .pk-trace-title-method { font-weight: 600; color: var(--pk-text-strong); font-size: 16px; }
        .pk-trace-title-path { font-family: var(--pk-font-mono); color: var(--pk-text); font-size: 14px; max-width: 600px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .pk-trace-title-traceid { font-family: var(--pk-font-mono); color: var(--pk-text-muted); font-size: 11px; background: var(--pk-bg); padding: 2px 8px; border-radius: 3px; }

        .pk-trace-meta {
            display: flex;
            align-items: center;
            gap: 16px;
            font-size: 12px;
        }

        .pk-trace-duration { font-family: var(--pk-font-mono); font-weight: 600; font-size: 16px; }
        .pk-trace-duration.warn { color: var(--pk-warning); }
        .pk-trace-duration.error { color: var(--pk-danger); }

        .pk-trace-status { padding: 3px 10px; border-radius: 4px; font-weight: 600; font-size: 13px; }
        .pk-trace-status.s2xx { background: var(--pk-success); color: #000; }
        .pk-trace-status.s3xx { background: var(--pk-warning); color: #000; }
        .pk-trace-status.s4xx, .pk-trace-status.s5xx { background: var(--pk-danger); color: #fff; }

        .pk-trace-close {
            position: absolute;
            top: 12px;
            right: 16px;
            background: transparent;
            border: none;
            color: var(--pk-text-muted);
            font-size: 28px;
            cursor: pointer;
            padding: 4px 8px;
            line-height: 1;
        }
        .pk-trace-close:hover { color: var(--pk-danger); }

        .pk-trace-tabs {
            display: flex;
            gap: 4px;
            padding: 0 20px;
            background: var(--pk-bg-alt);
            border-bottom: 1px solid var(--pk-border);
        }

        .pk-trace-tab {
            padding: 10px 16px;
            background: transparent;
            border: none;
            color: var(--pk-text-muted);
            cursor: pointer;
            font-size: 13px;
            border-bottom: 2px solid transparent;
            margin-bottom: -1px;
        }
        .pk-trace-tab:hover { color: var(--pk-text); }
        .pk-trace-tab.active { color: var(--pk-text-strong); border-bottom-color: var(--pk-primary); }
        .pk-trace-tab .count { margin-left: 6px; padding: 1px 6px; background: var(--pk-border); border-radius: 10px; font-size: 11px; }

        .pk-trace-content { flex: 1; overflow: auto; padding: 16px 20px; }

        .pk-trace-loading, .pk-trace-error { padding: 40px; text-align: center; color: var(--pk-text-muted); }
        .pk-trace-error { color: var(--pk-danger); }

        /* Gantt view styles */
        .pk-gantt { display: flex; flex-direction: column; gap: 2px; }
        .pk-gantt-header { display: flex; border-bottom: 1px solid var(--pk-border); padding-bottom: 8px; margin-bottom: 8px; }
        .pk-gantt-header-name { width: 350px; color: var(--pk-text-muted); font-size: 11px; text-transform: uppercase; }
        .pk-gantt-header-timeline { flex: 1; display: flex; justify-content: space-between; font-size: 11px; color: var(--pk-text-muted); font-family: var(--pk-font-mono); }

        .pk-gantt-row { display: flex; align-items: center; padding: 4px 0; border-radius: 4px; }
        .pk-gantt-row:hover { background: var(--pk-bg-alt); }
        .pk-gantt-row.collapsed + .pk-gantt-row.child { display: none; }

        .pk-gantt-toggle { width: 16px; cursor: pointer; user-select: none; color: var(--pk-text-muted); }
        .pk-gantt-toggle:hover { color: var(--pk-text); }

        .pk-gantt-name { width: 350px; display: flex; align-items: center; gap: 4px; font-size: 12px; overflow: hidden; }
        .pk-gantt-name-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .pk-gantt-kind { font-size: 10px; padding: 1px 4px; border-radius: 3px; text-transform: uppercase; }
        .pk-gantt-kind.server { background: var(--pk-primary); color: #000; }
        .pk-gantt-kind.client { background: var(--pk-purple); color: #fff; }
        .pk-gantt-kind.internal { background: var(--pk-border); color: var(--pk-text); }

        .pk-gantt-track { flex: 1; height: 22px; position: relative; background: var(--pk-bg-alt); border-radius: 3px; margin: 0 8px; }
        .pk-gantt-bar { position: absolute; height: 100%; border-radius: 3px; min-width: 3px; }
        .pk-gantt-bar.kind-server { background: var(--pk-primary); }
        .pk-gantt-bar.kind-client { background: var(--pk-purple); }
        .pk-gantt-bar.kind-internal, .pk-gantt-bar.kind-unknown { background: var(--pk-text-muted); }
        .pk-gantt-bar.has-error { background: var(--pk-danger); }

        .pk-gantt-duration { width: 60px; font-size: 11px; font-family: var(--pk-font-mono); color: var(--pk-text-muted); text-align: right; }

        .pk-gantt-event-marker { position: absolute; top: 0; bottom: 0; width: 2px; background: var(--pk-success); z-index: 1; cursor: pointer; }
        .pk-gantt-event-marker::after { content: ''; position: absolute; top: -3px; left: -3px; width: 8px; height: 8px; background: var(--pk-success); border-radius: 50%; border: 1px solid var(--pk-bg); }
        .pk-gantt-event-marker:hover::after { transform: scale(1.3); }
        .pk-gantt-event-tooltip { position: absolute; bottom: 100%; left: 50%; transform: translateX(-50%); background: var(--pk-bg-alt); border: 1px solid var(--pk-border); border-radius: 3px; padding: 4px 8px; font-size: 10px; white-space: nowrap; pointer-events: none; opacity: 0; transition: opacity 0.15s; z-index: 10; }
        .pk-gantt-event-marker:hover .pk-gantt-event-tooltip { opacity: 1; }

        .pk-gantt-badges { display: flex; flex-wrap: wrap; gap: 3px; margin-top: 2px; padding-left: 20px; }
        .pk-tag-badge { font-size: 9px; padding: 1px 5px; background: var(--pk-bg-hover); color: var(--pk-text-muted); border-radius: 3px; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .pk-tag-badge .key { color: var(--pk-primary); }
        .pk-tag-badge .value { color: var(--pk-text); }

        .pk-span-row-count { font-size: 10px; padding: 1px 5px; background: var(--pk-success); color: #000; border-radius: 3px; margin-left: 4px; }

        .pk-span-query-toggle { display: inline-flex; align-items: center; justify-content: center; padding: 2px 6px; background: transparent; border: 1px solid var(--pk-border); border-radius: var(--pk-radius); color: var(--pk-text-muted); font-size: 10px; cursor: pointer; margin-left: 4px; user-select: none; transition: all var(--pk-transition); }
        .pk-span-query-toggle:hover { background: var(--pk-bg-hover); color: var(--pk-text); border-color: var(--pk-text-muted); }
        .pk-span-query-toggle:active { background: var(--pk-bg-alt); }

        .pk-span-query-detail { padding: 8px 12px; margin: 4px 0 4px 36px; background: var(--pk-bg); border-left: 3px solid var(--pk-purple); border-radius: 0 var(--pk-radius) var(--pk-radius) 0; font-family: var(--pk-font-mono); font-size: 11px; white-space: pre-wrap; word-break: break-all; display: none; }
        .pk-span-query-detail.expanded { display: block; }
        .pk-span-query-detail .pk-query-label { color: var(--pk-text-muted); font-size: 10px; text-transform: uppercase; margin-bottom: 4px; }
        .pk-span-query-detail .pk-query-text { color: var(--pk-text-strong); }

        .pk-span-logs-toggle { display: inline-flex; align-items: center; justify-content: center; padding: 2px 6px; background: transparent; border: 1px solid var(--pk-border); border-radius: var(--pk-radius); color: var(--pk-text-muted); font-size: 10px; cursor: pointer; margin-left: 4px; user-select: none; transition: all var(--pk-transition); }
        .pk-span-logs-toggle:hover { background: var(--pk-bg-hover); color: var(--pk-text); border-color: var(--pk-text-muted); }
        .pk-span-logs-toggle:active { background: var(--pk-bg-alt); }

        /* Logs popup - fullscreen */
        .pk-logs-popup { position: fixed; top: 0; left: 0; width: 100vw; height: 100vh; background: var(--pk-bg); border: none; box-shadow: none; z-index: 1001; display: flex; flex-direction: column; }
        .pk-logs-popup.hidden { display: none; }
        .pk-logs-popup-header { display: flex; align-items: center; padding: 12px 20px; border-bottom: 1px solid var(--pk-border); background: var(--pk-bg); gap: 12px; }
        .pk-logs-popup-back { background: transparent; border: none; color: var(--pk-text-muted); font-size: 18px; cursor: pointer; padding: 4px 8px; line-height: 1; }
        .pk-logs-popup-back:hover { color: var(--pk-primary); }
        .pk-logs-popup-title { flex: 1; font-weight: 600; color: var(--pk-text-strong); font-size: 13px; }
        .pk-logs-popup-title code { font-family: var(--pk-font-mono); font-size: 12px; background: var(--pk-bg-alt); padding: 2px 6px; border-radius: 3px; }
        .pk-logs-popup-link { color: var(--pk-primary); cursor: pointer; text-decoration: underline; }
        .pk-logs-popup-link:hover { color: var(--pk-text-strong); }
        .pk-logs-popup-close { background: transparent; border: none; color: var(--pk-text-muted); font-size: 24px; cursor: pointer; padding: 0 4px; line-height: 1; }
        .pk-logs-popup-close:hover { color: var(--pk-danger); }
        .pk-logs-popup-content { flex: 1; overflow-y: auto; padding: 8px 20px; }
        .pk-log-item .pk-log-span { width: 120px; color: var(--pk-primary); cursor: pointer; font-family: var(--pk-font-mono); font-size: 10px; overflow: hidden; text-overflow: ellipsis; }
        .pk-log-item .pk-log-span:hover { text-decoration: underline; }

        /* Request tab styles */
        .pk-request-subtabs { display: flex; gap: 4px; margin-bottom: 16px; border-bottom: 1px solid var(--pk-border); }
        .pk-request-subtab { padding: 8px 12px; background: transparent; border: none; color: var(--pk-text-muted); cursor: pointer; font-size: 12px; border-bottom: 2px solid transparent; margin-bottom: -1px; }
        .pk-request-subtab:hover { color: var(--pk-text); }
        .pk-request-subtab.active { color: var(--pk-text-strong); border-bottom-color: var(--pk-primary); }

        .pk-request-section { margin-bottom: 24px; }
        .pk-request-section h3 { font-size: 11px; color: var(--pk-text-muted); margin: 0 0 8px 0; text-transform: uppercase; letter-spacing: 0.5px; }
        .pk-request-table { width: 100%; border-collapse: collapse; font-size: 12px; }
        .pk-request-table td { padding: 6px 8px; border-bottom: 1px solid var(--pk-bg-alt); }
        .pk-request-table td:first-child { width: 200px; color: var(--pk-text-muted); }
        .pk-request-table td:last-child { font-family: var(--pk-font-mono); word-break: break-all; }
        .pk-request-masked { color: var(--pk-text-muted); font-style: italic; }
        .pk-controller-info { font-family: var(--pk-font-mono); font-size: 13px; color: var(--pk-text); padding: 8px 12px; background: var(--pk-bg-alt); border-radius: var(--pk-radius); }

        /* Queries tab styles */
        .pk-query-item { margin-bottom: 16px; padding: 12px; background: var(--pk-bg-alt); border-radius: var(--pk-radius); border-left: 3px solid var(--pk-purple); }
        .pk-query-header { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 12px; }
        .pk-query-system { color: var(--pk-text-muted); }
        .pk-query-meta { display: flex; gap: 12px; align-items: center; }
        .pk-query-duration { font-family: var(--pk-font-mono); font-weight: 600; }
        .pk-query-duration.slow { color: var(--pk-warning); }
        .pk-query-duration.very-slow { color: var(--pk-danger); }
        .pk-query-rows { font-family: var(--pk-font-mono); color: var(--pk-success); font-size: 11px; }
        .pk-query-sql { font-family: var(--pk-font-mono); font-size: 12px; background: var(--pk-bg); padding: 10px; border-radius: var(--pk-radius); white-space: pre-wrap; word-break: break-all; overflow-x: auto; color: var(--pk-text-strong); }
        .pk-query-params { margin-top: 8px; font-size: 11px; color: var(--pk-text-muted); }

        /* Logs tab styles */
        .pk-logs-filter { display: flex; gap: 12px; margin-bottom: 16px; align-items: center; flex-wrap: wrap; }
        .pk-logs-filter input, .pk-logs-filter select { padding: 6px 10px; background: var(--pk-bg-alt); border: 1px solid var(--pk-border); border-radius: var(--pk-radius); color: var(--pk-text); font-size: 12px; }
        .pk-logs-filter input { flex: 1; max-width: 300px; }
        .pk-logs-filter input::placeholder { color: var(--pk-text-muted); }
        .pk-logs-filter-span { display: flex; align-items: center; gap: 6px; padding: 4px 10px; background: var(--pk-primary); color: #000; border-radius: var(--pk-radius); font-size: 11px; }
        .pk-logs-filter-span-clear { cursor: pointer; font-weight: bold; }
        .pk-logs-filter-span-clear:hover { color: var(--pk-danger); }

        .pk-log-group { margin-bottom: 8px; }
        .pk-log-group-header { padding: 8px 12px; background: var(--pk-bg-alt); border-radius: var(--pk-radius); cursor: pointer; display: flex; justify-content: space-between; font-size: 12px; font-weight: 500; }
        .pk-log-group-header:hover { background: var(--pk-bg-hover); }
        .pk-log-group-header .arrow { transition: transform 0.2s; }
        .pk-log-group-header.collapsed .arrow { transform: rotate(-90deg); }

        .pk-log-group-list { padding-left: 0; }
        .pk-log-group-list.collapsed { display: none; }

        .pk-log-item { display: flex; gap: 12px; padding: 6px 12px; font-family: var(--pk-font-mono); font-size: 11px; border-bottom: 1px solid var(--pk-bg-alt); }
        .pk-log-item:hover { background: var(--pk-bg-alt); }
        .pk-log-item.hidden { display: none; }
        .pk-log-time { color: var(--pk-text-muted); white-space: nowrap; width: 90px; }
        .pk-log-span-link { width: 100px; color: var(--pk-primary); cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .pk-log-span-link:hover { text-decoration: underline; }
        .pk-log-level { width: 50px; font-weight: 600; }
        .pk-log-level.DEBUG { color: var(--pk-text-muted); }
        .pk-log-level.INFO { color: var(--pk-primary); }
        .pk-log-level.WARN { color: var(--pk-warning); }
        .pk-log-level.ERROR { color: var(--pk-danger); }
        .pk-log-message { flex: 1; word-break: break-word; }

        .pk-no-data { padding: 40px; text-align: center; color: var(--pk-text-muted); }
    `;

    const ROOT_ACTION_ICONS = {
        HTTP_REQUEST: '&#127760;',
        SCHEDULED_JOB: '&#128337;',
        MESSAGE_CONSUMER: '&#128233;',
        RPC_CALL: '&#128279;',
        DATABASE: '&#128450;',
        INTERNAL: '&#9881;',
        UNKNOWN: '&#10067;'
    };

    function openTraceDetail(traceId, options = {}) {
        closeTraceDetail();

        const overlay = document.createElement('div');
        overlay.id = 'peekaboot-trace-overlay';
        document.body.appendChild(overlay);

        const shadow = overlay.attachShadow({ mode: 'open' });

        const style = document.createElement('style');
        style.textContent = STYLES;
        shadow.appendChild(style);

        const loading = document.createElement('div');
        loading.className = 'pk-trace-overlay';
        loading.innerHTML = '<div class="pk-trace-loading">Loading trace data...</div>';
        shadow.appendChild(loading);

        fetchAndRender(shadow, traceId, options);
    }

    function closeTraceDetail() {
        const existing = document.getElementById('peekaboot-trace-overlay');
        if (existing) {
            existing.remove();
        }
    }

    async function fetchAndRender(shadow, traceId, options) {
        try {
            const basePath = options.basePath || '/peekaboot';
            const response = await fetch(`${basePath}/api/traces/${traceId}/insights`);
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const trace = await response.json();
            render(shadow, trace, options);
        } catch (error) {
            shadow.innerHTML = '';
            const style = document.createElement('style');
            style.textContent = STYLES;
            shadow.appendChild(style);
            const errorDiv = document.createElement('div');
            errorDiv.className = 'pk-trace-overlay';
            errorDiv.innerHTML = `<div class="pk-trace-error">Failed to load trace: ${Utils.escapeHtml(error.message)}<br><br><button onclick="this.closest('#peekaboot-trace-overlay').remove()" style="padding:8px 16px;cursor:pointer">Close</button></div>`;
            shadow.appendChild(errorDiv);
        }
    }

    function render(shadow, trace, options) {
        const rootSpan = trace.rootSpan || {};
        const tags = rootSpan.tags || {};
        const httpExchange = trace.httpExchange || {};
        const req = httpExchange.request || {};
        const res = httpExchange.response || {};
        // Prefer httpExchange data, fall back to span tags
        const method = req.method || tags['http.method'] || tags['http.request.method'] || 'UNKNOWN';
        const path = req.path || tags['http.target'] || tags['url.path'] || rootSpan.name || '-';
        const status = res.status || res.statusCode || tags['http.status_code'] || tags['http.response.status_code'] || '-';
        const statusClass = 's' + Math.floor(parseInt(status) / 100) + 'xx';
        const icon = ROOT_ACTION_ICONS[trace.rootActionType] || ROOT_ACTION_ICONS.UNKNOWN;
        const durationClass = trace.durationMs > 500 ? 'error' : (trace.durationMs > 200 ? 'warn' : '');

        const queryCount = (trace.queries || []).length;
        const logCount = (trace.logs || []).length;
        const spanCount = trace.summary?.spans?.count || countSpans(trace.rootSpan);

        shadow.innerHTML = '';
        const style = document.createElement('style');
        style.textContent = STYLES;
        shadow.appendChild(style);

        const container = document.createElement('div');
        container.className = 'pk-trace-overlay';
        container.innerHTML = `
            <div class="pk-trace-container">
                <div class="pk-trace-header">
                    <button class="pk-trace-back" title="Back">&#8592;</button>
                    <div class="pk-trace-title">
                        <span class="pk-trace-title-icon">${icon}</span>
                        <span class="pk-trace-title-method">${Utils.escapeHtml(method)}</span>
                        <span class="pk-trace-title-path" title="${Utils.escapeHtml(path)}">${Utils.escapeHtml(path)}</span>
                        <span class="pk-trace-title-traceid" title="${Utils.escapeHtml(trace.traceId || '')}">${Utils.escapeHtml(trace.traceId || '-')}</span>
                    </div>
                    <div class="pk-trace-meta">
                        <span class="pk-trace-duration ${durationClass}">${trace.durationMs}ms</span>
                        <span class="pk-trace-status ${statusClass}">${status}</span>
                        <span>${spanCount} spans</span>
                        <span>${queryCount} queries</span>
                        <span>${logCount} logs</span>
                    </div>
                    <button class="pk-trace-close" title="Close">&times;</button>
                </div>
                <div class="pk-trace-tabs">
                    <button class="pk-trace-tab" data-tab="request">Request</button>
                    <button class="pk-trace-tab active" data-tab="spans">Spans</button>
                    <button class="pk-trace-tab" data-tab="queries">Queries <span class="count">${queryCount}</span></button>
                    <button class="pk-trace-tab" data-tab="logs">Logs <span class="count">${logCount}</span></button>
                </div>
                <div class="pk-trace-content" id="pk-tab-content"></div>
                <div id="pk-logs-popup" class="pk-logs-popup hidden"></div>
            </div>
        `;
        shadow.appendChild(container);

        container.querySelector('.pk-trace-back').addEventListener('click', closeTraceDetail);
        container.querySelector('.pk-trace-close').addEventListener('click', closeTraceDetail);
        container.addEventListener('click', (e) => {
            if (e.target === container) closeTraceDetail();
        });

        let activeTab = 'spans';
        const tabs = container.querySelectorAll('.pk-trace-tab');
        const content = container.querySelector('#pk-tab-content');

        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                tabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                activeTab = tab.dataset.tab;
                renderTabContent(content, activeTab, trace);
            });
        });

        renderTabContent(content, activeTab, trace);

        // ESC key to close
        const escHandler = (e) => {
            if (e.key === 'Escape') {
                closeTraceDetail();
                document.removeEventListener('keydown', escHandler);
            }
        };
        document.addEventListener('keydown', escHandler);
    }

    function renderTabContent(container, tab, trace) {
        switch (tab) {
            case 'request': renderRequest(container, trace); break;
            case 'spans': renderSpans(container, trace); break;
            case 'queries': renderQueries(container, trace); break;
            case 'logs': renderLogs(container, trace); break;
        }
    }

    function renderSpans(container, trace) {
        const totalDuration = trace.durationMs || 1;
        const traceStart = trace.startTimeMs || 0;
        const traceId = trace.traceId || '';
        const allTraceLogs = trace.logs || [];
        const markers = [0, 0.25, 0.5, 0.75, 1].map(p => Math.round(totalDuration * p) + 'ms');

        let html = '<div class="pk-gantt">';
        html += '<div class="pk-gantt-header">';
        html += '<div class="pk-gantt-header-name">Span</div>';
        html += '<div class="pk-gantt-header-timeline">' + markers.map(m => `<span>${m}</span>`).join('') + '</div>';
        html += '<div style="width:60px"></div>';
        html += '</div>';
        html += '<div id="pk-gantt-rows"></div>';
        html += '</div>';
        container.innerHTML = html;

        const rowsContainer = container.querySelector('#pk-gantt-rows');
        renderSpanRows(rowsContainer, trace.rootSpan, 0, traceStart, totalDuration, null);

        // Add click handlers for expand/collapse
        rowsContainer.addEventListener('click', (e) => {
            // Handle logs toggle clicks
            const logsToggle = e.target.closest('.pk-span-logs-toggle');
            if (logsToggle) {
                const spanId = logsToggle.dataset.spanId;
                const logsBase64 = logsToggle.dataset.logs;
                // Decode base64 JSON (handles UTF-8 properly)
                const spanLogs = logsBase64 ? JSON.parse(decodeURIComponent(escape(atob(logsBase64)))) : [];
                showSpanLogsPopup(container, traceId, spanId, spanLogs, allTraceLogs);
                return;
            }

            // Handle SQL toggle clicks
            const sqlToggle = e.target.closest('.pk-span-query-toggle');
            if (sqlToggle) {
                const spanId = sqlToggle.dataset.spanId;
                const queryDetail = rowsContainer.querySelector(`.pk-span-query-detail[data-span-id="${spanId}"]`);
                if (queryDetail) {
                    queryDetail.classList.toggle('expanded');
                    sqlToggle.title = queryDetail.classList.contains('expanded') ? 'Hide SQL' : 'Show SQL';
                }
                return;
            }

            // Handle row expand/collapse
            const toggle = e.target.closest('.pk-gantt-toggle');
            if (!toggle) return;
            const row = toggle.closest('.pk-gantt-row');
            const spanId = row.dataset.spanId;
            const isCollapsed = toggle.textContent === '+';

            toggle.textContent = isCollapsed ? '-' : '+';

            // Show/hide descendant rows
            let sibling = row.nextElementSibling;
            const rowDepth = parseInt(row.dataset.depth);
            while (sibling && parseInt(sibling.dataset.depth) > rowDepth) {
                if (isCollapsed) {
                    sibling.style.display = '';
                    // If this row has a collapsed toggle, skip its children
                    const sibToggle = sibling.querySelector('.pk-gantt-toggle');
                    if (sibToggle && sibToggle.textContent === '+') {
                        const sibDepth = parseInt(sibling.dataset.depth);
                        sibling = sibling.nextElementSibling;
                        while (sibling && parseInt(sibling.dataset.depth) > sibDepth) {
                            sibling = sibling.nextElementSibling;
                        }
                        continue;
                    }
                } else {
                    sibling.style.display = 'none';
                }
                sibling = sibling.nextElementSibling;
            }
        });
    }

    function showSpanLogsPopup(container, traceId, spanId, spanLogs, allTraceLogs) {
        // Find popup in the trace container (parent of tab content)
        const traceContainer = container.closest('.pk-trace-container');
        const popup = traceContainer ? traceContainer.querySelector('#pk-logs-popup') : null;
        if (!popup) return;

        // Render function that can show either span logs or all trace logs
        function renderLogs(showAllLogs, filterSpanId) {
            const logs = showAllLogs ? allTraceLogs : spanLogs;

            if (logs.length === 0) {
                popup.classList.add('hidden');
                return;
            }

            let html = '<div class="pk-logs-popup-header">';
            html += '<button class="pk-logs-popup-back" title="Back">&#8592;</button>';

            if (showAllLogs) {
                // Showing all trace logs
                html += `<span class="pk-logs-popup-title">Logs for Trace <code>${Utils.escapeHtml(traceId)}</code></span>`;
            } else {
                // Showing span-specific logs
                html += `<span class="pk-logs-popup-title">Logs for Span <code>${Utils.escapeHtml(spanId)}</code> (Part of trace <code>${Utils.escapeHtml(traceId)}</code>). <span class="pk-logs-popup-link" id="pk-show-all-logs">Show logs for all spans.</span></span>`;
            }

            html += '<button class="pk-logs-popup-close" title="Close">&times;</button>';
            html += '</div>';
            html += '<div class="pk-logs-popup-content">';

            logs.forEach(log => {
                const time = formatTime(log.timestamp);
                const logSpanId = log.spanId || '';
                html += `<div class="pk-log-item">`;
                html += `<span class="pk-log-time">${time}</span>`;
                if (showAllLogs) {
                    // Show spanId column when viewing all logs
                    const shortSpanId = logSpanId.length > 12 ? logSpanId.substring(0, 12) + '...' : logSpanId;
                    html += `<span class="pk-log-span" data-span-id="${Utils.escapeHtml(logSpanId)}" title="${Utils.escapeHtml(logSpanId)}">${Utils.escapeHtml(shortSpanId)}</span>`;
                }
                html += `<span class="pk-log-level ${log.level}">${log.level}</span>`;
                html += `<span class="pk-log-message">${Utils.escapeHtml(log.message)}</span>`;
                html += `</div>`;
            });

            html += '</div>';
            popup.innerHTML = html;
            popup.classList.remove('hidden');

            // Close handlers (both back and close buttons)
            const closePopup = () => popup.classList.add('hidden');
            popup.querySelector('.pk-logs-popup-back').addEventListener('click', closePopup);
            popup.querySelector('.pk-logs-popup-close').addEventListener('click', closePopup);

            // "Show logs for all spans" link handler
            const showAllLink = popup.querySelector('#pk-show-all-logs');
            if (showAllLink) {
                showAllLink.addEventListener('click', () => renderLogs(true, null));
            }

            // SpanId click handler to filter to that span's logs
            if (showAllLogs) {
                popup.querySelectorAll('.pk-log-span').forEach(el => {
                    el.addEventListener('click', () => {
                        const clickedSpanId = el.dataset.spanId;
                        if (clickedSpanId) {
                            // Filter to show only logs for this span
                            const filteredLogs = allTraceLogs.filter(l => l.spanId === clickedSpanId);
                            showFilteredSpanLogs(clickedSpanId, filteredLogs);
                        }
                    });
                });
            }

            // Click outside to close
            popup.addEventListener('click', (e) => {
                if (e.target === popup) {
                    popup.classList.add('hidden');
                }
            });
        }

        // Show filtered span logs (when clicking on spanId in the all-logs view)
        function showFilteredSpanLogs(filteredSpanId, filteredLogs) {
            if (filteredLogs.length === 0) {
                popup.classList.add('hidden');
                return;
            }

            let html = '<div class="pk-logs-popup-header">';
            html += '<button class="pk-logs-popup-back" title="Back">&#8592;</button>';
            html += `<span class="pk-logs-popup-title">Logs for Span <code>${Utils.escapeHtml(filteredSpanId)}</code> (Part of trace <code>${Utils.escapeHtml(traceId)}</code>). <span class="pk-logs-popup-link" id="pk-show-all-logs">Show logs for all spans.</span></span>`;
            html += '<button class="pk-logs-popup-close" title="Close">&times;</button>';
            html += '</div>';
            html += '<div class="pk-logs-popup-content">';

            filteredLogs.forEach(log => {
                const time = formatTime(log.timestamp);
                html += `<div class="pk-log-item">`;
                html += `<span class="pk-log-time">${time}</span>`;
                html += `<span class="pk-log-level ${log.level}">${log.level}</span>`;
                html += `<span class="pk-log-message">${Utils.escapeHtml(log.message)}</span>`;
                html += `</div>`;
            });

            html += '</div>';
            popup.innerHTML = html;

            // Close handlers
            const closePopup = () => popup.classList.add('hidden');
            popup.querySelector('.pk-logs-popup-back').addEventListener('click', closePopup);
            popup.querySelector('.pk-logs-popup-close').addEventListener('click', closePopup);

            // "Show logs for all spans" link handler
            const showAllLink = popup.querySelector('#pk-show-all-logs');
            if (showAllLink) {
                showAllLink.addEventListener('click', () => renderLogs(true, null));
            }

            // Click outside to close
            popup.addEventListener('click', (e) => {
                if (e.target === popup) {
                    popup.classList.add('hidden');
                }
            });
        }

        // Initial render showing span-specific logs
        renderLogs(false, spanId);
    }

    function renderSpanRows(container, span, depth, traceStart, totalDuration, parentId) {
        if (!span) return;

        const indent = depth * 20;
        const spanStart = span.startTimeMs || traceStart;
        const spanDuration = span.durationMs || 0;
        const left = Math.max(0, ((spanStart - traceStart) / totalDuration) * 100);
        const width = Math.max((spanDuration / totalDuration) * 100, 0.5);
        const kindRaw = span.kind || 'internal';
        const kind = kindRaw.toLowerCase();
        const hasError = span.status === 'ERROR' || span.errorMessage;
        const hasChildren = span.children && span.children.length > 0;
        const events = span.events || [];
        const tags = span.tags || {};

        // Check for query spans and result-set spans
        const spanName = (span.name || '').toLowerCase();
        const isQuerySpan = spanName === 'query' || spanName.includes('query');
        const isResultSetSpan = spanName === 'result-set' || spanName.includes('result-set');
        const queryTags = Object.entries(tags).filter(([k]) => k.startsWith('jdbc.query'));
        const hasQuery = queryTags.length > 0;
        const rowCount = tags['jdbc.row-count'];

        // Check for logs (now attached to span by backend)
        const spanLogs = span.logs || [];
        const hasLogs = spanLogs.length > 0;

        const row = document.createElement('div');
        row.className = 'pk-gantt-row';
        row.dataset.spanId = span.spanId;
        row.dataset.depth = depth;
        if (parentId) row.dataset.parentId = parentId;

        let nameHtml = `<div class="pk-gantt-name" style="padding-left: ${indent}px">`;
        if (hasChildren) {
            nameHtml += `<span class="pk-gantt-toggle">-</span>`;
        } else {
            nameHtml += `<span style="width:16px"></span>`;
        }
        if (kind !== 'internal' && kind !== 'unknown' && kind !== 'null') {
            nameHtml += `<span class="pk-gantt-kind ${kind}">${kind}</span>`;
        }
        nameHtml += `<span class="pk-gantt-name-text" title="${Utils.escapeHtml(span.name || 'unknown')}">${Utils.escapeHtml(span.name || 'unknown')}</span>`;

        // Add row count badge for result-set spans
        if (isResultSetSpan && rowCount !== undefined) {
            nameHtml += `<span class="pk-span-row-count">${rowCount} rows</span>`;
        }

        // Add query toggle for query spans with SQL
        if (hasQuery) {
            nameHtml += `<span class="pk-span-query-toggle" data-span-id="${span.spanId}" title="Show SQL">&#128196;</span>`;
        }

        // Add logs toggle for spans with logs
        if (hasLogs) {
            // Store logs as base64-encoded JSON to avoid HTML attribute escaping issues
            const logsBase64 = btoa(unescape(encodeURIComponent(JSON.stringify(spanLogs))));
            nameHtml += `<span class="pk-span-logs-toggle" data-span-id="${span.spanId}" data-logs="${logsBase64}">${spanLogs.length} logs</span>`;
        }

        nameHtml += `</div>`;

        // Build track HTML with bar and event markers
        let trackHtml = `<div class="pk-gantt-track">`;
        trackHtml += `<div class="pk-gantt-bar kind-${kind}${hasError ? ' has-error' : ''}" style="left: ${left}%; width: ${width}%"></div>`;

        // Add event markers on the timeline
        events.forEach(event => {
            if (event.timestamp) {
                const eventTimeMs = new Date(event.timestamp).getTime();
                // Position relative to the entire trace timeline
                const eventLeft = Math.max(0, Math.min(100, ((eventTimeMs - traceStart) / totalDuration) * 100));
                trackHtml += `<div class="pk-gantt-event-marker" style="left: ${eventLeft}%"><span class="pk-gantt-event-tooltip">${Utils.escapeHtml(event.name)}</span></div>`;
            }
        });
        trackHtml += `</div>`;

        row.innerHTML = nameHtml + trackHtml + `<span class="pk-gantt-duration">${spanDuration}ms</span>`;

        container.appendChild(row);

        // Add query detail row (hidden by default) for query spans
        if (hasQuery) {
            const queryDetail = document.createElement('div');
            queryDetail.className = 'pk-span-query-detail';
            queryDetail.dataset.spanId = span.spanId;
            queryDetail.dataset.depth = depth;
            queryDetail.style.marginLeft = (indent + 20) + 'px';

            let queryHtml = '';
            queryTags.forEach(([key, value]) => {
                const label = key.replace('jdbc.query', 'Query').replace('[', ' ').replace(']', '');
                queryHtml += `<div class="pk-query-label">${Utils.escapeHtml(label)}</div>`;
                queryHtml += `<div class="pk-query-text">${Utils.escapeHtml(value)}</div>`;
            });
            queryDetail.innerHTML = queryHtml;
            container.appendChild(queryDetail);
        }

        // Add tags row if present (events are now shown as markers on the timeline)
        const tagEntries = Object.entries(tags).filter(([k]) => !k.startsWith('jdbc.query'));
        const hasTags = tagEntries.length > 0;

        if (hasTags) {
            const badgesRow = document.createElement('div');
            badgesRow.className = 'pk-gantt-badges';
            badgesRow.style.paddingLeft = (indent + 20) + 'px';
            badgesRow.dataset.depth = depth;
            if (parentId) badgesRow.dataset.parentId = parentId;

            let badgesHtml = '';
            // Render all tags
            tagEntries.forEach(([key, value]) => {
                const shortKey = key.split('.').pop();
                const shortVal = String(value).length > 50 ? String(value).substring(0, 50) + '...' : String(value);
                badgesHtml += `<span class="pk-tag-badge" title="${Utils.escapeHtml(key)}: ${Utils.escapeHtml(value)}"><span class="key">${Utils.escapeHtml(shortKey)}</span>=<span class="value">${Utils.escapeHtml(shortVal)}</span></span>`;
            });

            badgesRow.innerHTML = badgesHtml;
            container.appendChild(badgesRow);
        }

        if (hasChildren) {
            span.children.forEach(child => {
                renderSpanRows(container, child, depth + 1, traceStart, totalDuration, span.spanId);
            });
        }
    }

    function renderRequest(container, trace) {
        const httpExchange = trace.httpExchange;
        const req = httpExchange?.request;
        const res = httpExchange?.response;

        // Build sub-tab navigation
        let html = '<div class="pk-request-subtabs">';
        html += '<button class="pk-request-subtab active" data-subtab="overview">Overview</button>';
        html += '<button class="pk-request-subtab" data-subtab="request-headers">Request Headers</button>';
        html += '<button class="pk-request-subtab" data-subtab="response-headers">Response Headers</button>';
        html += '</div>';
        html += '<div id="pk-request-subtab-content"></div>';

        container.innerHTML = html;

        // Add sub-tab click handlers
        const subtabs = container.querySelectorAll('.pk-request-subtab');
        const subtabContent = container.querySelector('#pk-request-subtab-content');

        subtabs.forEach(tab => {
            tab.addEventListener('click', () => {
                subtabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
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
        html += '<div class="pk-request-section">';
        html += '<h3>Request</h3>';
        html += '<table class="pk-request-table">';
        html += `<tr><td>Method</td><td>${Utils.escapeHtml(req?.method || '-')}</td></tr>`;
        html += `<tr><td>Path</td><td>${Utils.escapeHtml(req?.path || '-')}</td></tr>`;
        if (req?.query) {
            html += `<tr><td>Query String</td><td>${Utils.escapeHtml(req.query)}</td></tr>`;
        }
        html += `<tr><td>Status</td><td>${Utils.escapeHtml(String(res?.status || res?.statusCode || '-'))}</td></tr>`;
        const contentType = req?.headers?.['content-type'] || req?.headers?.['Content-Type'] || '-';
        html += `<tr><td>Content-Type</td><td>${Utils.escapeHtml(contentType)}</td></tr>`;
        html += `<tr><td>Duration</td><td>${trace.durationMs || '-'}ms</td></tr>`;
        html += '</table></div>';

        // Controller info (API returns 'class' and 'method' fields)
        if (req?.controller?.class || req?.controller?.method) {
            html += '<div class="pk-request-section">';
            html += '<h3>Controller</h3>';
            html += `<div class="pk-controller-info">${Utils.escapeHtml(req.controller.class || 'Unknown')}.${Utils.escapeHtml(req.controller.method || 'unknown')}()</div>`;
            html += '</div>';
        }

        // Query Parameters
        const queryParams = req?.params?.query || {};
        if (Object.keys(queryParams).length > 0) {
            html += '<div class="pk-request-section">';
            html += '<h3>Query Parameters</h3>';
            html += '<table class="pk-request-table">';
            Object.entries(queryParams).sort().forEach(([k, v]) => {
                const displayValue = Array.isArray(v) ? v.join(', ') : v;
                html += `<tr><td>${Utils.escapeHtml(k)}</td><td>${Utils.escapeHtml(displayValue)}</td></tr>`;
            });
            html += '</table></div>';
        }

        // Form Parameters
        const formParams = req?.params?.form || {};
        if (Object.keys(formParams).length > 0) {
            html += '<div class="pk-request-section">';
            html += '<h3>Form Parameters</h3>';
            html += '<table class="pk-request-table">';
            Object.entries(formParams).sort().forEach(([k, v]) => {
                const displayValue = Array.isArray(v) ? v.join(', ') : v;
                html += `<tr><td>${Utils.escapeHtml(k)}</td><td>${Utils.escapeHtml(displayValue)}</td></tr>`;
            });
            html += '</table></div>';
        }

        // Uploaded Files
        const files = req?.params?.upload || req?.params?.files || [];
        if (files.length > 0) {
            html += '<div class="pk-request-section">';
            html += '<h3>Uploaded Files</h3>';
            html += '<table class="pk-request-table">';
            files.forEach(file => {
                const filename = file.originalFilename || file.name || 'unknown';
                html += `<tr><td>${Utils.escapeHtml(filename)}</td><td>${Utils.escapeHtml(file.contentType || '-')} (${Utils.escapeHtml(String(file.size || 0))} bytes)</td></tr>`;
            });
            html += '</table></div>';
        }

        // Request Body
        if (req?.body?.content) {
            html += '<div class="pk-request-section">';
            html += '<h3>Request Body' + (req.body.truncated ? ' <span class="pk-request-masked">(truncated)</span>' : '') + '</h3>';
            html += `<div class="pk-query-sql">${Utils.escapeHtml(req.body.content)}</div>`;
            html += '</div>';
        }

        container.innerHTML = html || '<div class="pk-no-data">No request details available</div>';
    }

    function renderRequestHeaders(container, req) {
        let html = '<div class="pk-request-section">';
        html += '<h3>Request Headers</h3>';
        html += '<table class="pk-request-table">';
        const reqHeaders = req?.headers || {};
        if (Object.keys(reqHeaders).length > 0) {
            Object.entries(reqHeaders).sort().forEach(([k, v]) => {
                const isMasked = v === '********';
                html += `<tr><td>${Utils.escapeHtml(k)}</td><td class="${isMasked ? 'pk-request-masked' : ''}">${Utils.escapeHtml(v)}</td></tr>`;
            });
        } else {
            html += '<tr><td colspan="2" class="pk-request-masked">No headers captured</td></tr>';
        }
        html += '</table></div>';
        container.innerHTML = html;
    }

    function renderResponseHeaders(container, res) {
        let html = '<div class="pk-request-section">';
        html += '<h3>Response Headers</h3>';
        html += '<table class="pk-request-table">';
        const resHeaders = res?.headers || {};
        if (Object.keys(resHeaders).length > 0) {
            Object.entries(resHeaders).sort().forEach(([k, v]) => {
                html += `<tr><td>${Utils.escapeHtml(k)}</td><td>${Utils.escapeHtml(v)}</td></tr>`;
            });
        } else {
            html += '<tr><td colspan="2" class="pk-request-masked">No headers captured</td></tr>';
        }
        html += '</table></div>';
        container.innerHTML = html;
    }

    function renderQueries(container, trace) {
        const queries = trace.queries || [];

        if (queries.length === 0) {
            container.innerHTML = '<div class="pk-no-data">No database queries recorded</div>';
            return;
        }

        let html = '';
        queries.forEach((query, idx) => {
            const sql = query.sql || 'Unknown query';
            const duration = query.durationMs || 0;
            const durationClass = Utils.getDurationClass(duration);
            const system = query.dbSystem || 'SQL';
            const rowCount = query.rowCount;

            html += '<div class="pk-query-item">';
            html += '<div class="pk-query-header">';
            html += `<span class="pk-query-system">${idx + 1}. ${Utils.escapeHtml(system.toUpperCase())}</span>`;
            html += '<span class="pk-query-meta">';
            html += `<span class="pk-query-duration ${durationClass}">${duration}ms${duration > 100 ? ' SLOW' : ''}</span>`;
            if (rowCount !== null && rowCount !== undefined) {
                html += `<span class="pk-query-rows">${rowCount} rows</span>`;
            }
            html += '</span>';
            html += '</div>';
            html += `<div class="pk-query-sql">${Utils.escapeHtml(sql)}</div>`;
            html += '</div>';
        });

        container.innerHTML = html;
    }

    function renderLogs(container, trace) {
        const logs = trace.logs || [];

        if (logs.length === 0) {
            container.innerHTML = '<div class="pk-no-data">No logs recorded for this trace</div>';
            return;
        }

        // Build span name lookup
        const spanNames = new Map();
        function mapSpanNames(span) {
            if (!span) return;
            spanNames.set(span.spanId, span.name);
            (span.children || []).forEach(mapSpanNames);
        }
        mapSpanNames(trace.rootSpan);

        let currentSpanFilter = null;

        function render() {
            let html = '<div class="pk-logs-filter">';
            html += '<input type="text" placeholder="Filter logs..." id="pk-log-filter">';
            html += '<select id="pk-log-level"><option value="">All Levels</option><option>ERROR</option><option>WARN</option><option>INFO</option><option>DEBUG</option></select>';
            if (currentSpanFilter) {
                const spanName = spanNames.get(currentSpanFilter) || currentSpanFilter;
                const shortName = spanName.length > 20 ? spanName.substring(0, 20) + '...' : spanName;
                html += `<span class="pk-logs-filter-span">Span: ${Utils.escapeHtml(shortName)} <span class="pk-logs-filter-span-clear" id="pk-clear-span-filter">&times;</span></span>`;
            }
            html += '</div>';
            html += '<div id="pk-logs-list">';

            logs.forEach(log => {
                const logSpanId = log.spanId || '';
                const spanName = spanNames.get(logSpanId) || logSpanId;
                const shortSpanName = spanName.length > 15 ? spanName.substring(0, 15) + '...' : spanName;
                const time = formatTime(log.timestamp);
                const hiddenClass = currentSpanFilter && logSpanId !== currentSpanFilter ? ' hidden' : '';

                html += `<div class="pk-log-item${hiddenClass}" data-level="${log.level}" data-span-id="${Utils.escapeHtml(logSpanId)}">`;
                html += `<span class="pk-log-time">${time}</span>`;
                html += `<span class="pk-log-span-link" data-span-id="${Utils.escapeHtml(logSpanId)}" title="${Utils.escapeHtml(spanName)}">${Utils.escapeHtml(shortSpanName)}</span>`;
                html += `<span class="pk-log-level ${log.level}">${log.level}</span>`;
                html += `<span class="pk-log-message">${Utils.escapeHtml(log.message)}</span>`;
                html += '</div>';
            });

            html += '</div>';
            container.innerHTML = html;

            // Filter controls
            const filterInput = container.querySelector('#pk-log-filter');
            const levelSelect = container.querySelector('#pk-log-level');
            const clearSpanFilter = container.querySelector('#pk-clear-span-filter');

            function applyFilters() {
                const text = filterInput.value.toLowerCase();
                const level = levelSelect.value;
                container.querySelectorAll('.pk-log-item').forEach(item => {
                    const message = item.querySelector('.pk-log-message').textContent.toLowerCase();
                    const itemLevel = item.dataset.level;
                    const itemSpanId = item.dataset.spanId;
                    const matchText = !text || message.includes(text);
                    const matchLevel = !level || itemLevel === level;
                    const matchSpan = !currentSpanFilter || itemSpanId === currentSpanFilter;
                    item.classList.toggle('hidden', !(matchText && matchLevel && matchSpan));
                });
            }

            filterInput.addEventListener('input', applyFilters);
            levelSelect.addEventListener('change', applyFilters);

            // Clear span filter
            if (clearSpanFilter) {
                clearSpanFilter.addEventListener('click', () => {
                    currentSpanFilter = null;
                    render();
                });
            }

            // Span click to filter
            container.querySelectorAll('.pk-log-span-link').forEach(el => {
                el.addEventListener('click', () => {
                    const spanId = el.dataset.spanId;
                    if (spanId) {
                        currentSpanFilter = spanId;
                        render();
                    }
                });
            });
        }

        render();
    }

    function countSpans(span) {
        if (!span) return 0;
        let count = 1;
        (span.children || []).forEach(child => {
            count += countSpans(child);
        });
        return count;
    }

    function formatTime(timestamp) {
        if (!timestamp) return '-';
        try {
            const date = new Date(timestamp);
            return date.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });
        } catch (e) {
            return String(timestamp).substring(11, 23);
        }
    }

    global.PeekabootTraceDetail = {
        open: openTraceDetail,
        close: closeTraceDetail
    };

})(window);
