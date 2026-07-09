(function() {
    'use strict';

    const host = document.getElementById('peekaboot-toolbar-host');
    if (!host || !host.shadowRoot) return;

    const shadow = host.shadowRoot;
    const data = JSON.parse(document.getElementById('peekaboot-toolbar-data').textContent);
    let traceData = null;
    let activeTab = 'spans';

    // Design tokens matching peekaboot.css (dark theme for toolbar)
    const expandedStyles = document.createElement('style');
    expandedStyles.textContent = `
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
            --pk-font: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            --pk-font-mono: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, monospace;
            --pk-radius: 6px;
        }

        .peekaboot-expanded {
            position: fixed;
            top: 0;
            left: 0;
            width: 100vw;
            height: 100vh;
            background: var(--pk-bg);
            color: var(--pk-text);
            font: 13px/1.5 var(--pk-font);
            display: flex;
            flex-direction: column;
            z-index: 2147483647;
        }

        .peekaboot-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 8px 16px;
            background: var(--pk-bg-alt);
            border-bottom: 1px solid var(--pk-border);
        }

        .peekaboot-back {
            background: transparent;
            border: none;
            color: var(--pk-text-muted);
            cursor: pointer;
            padding: 4px 8px;
            font-size: 18px;
            margin-right: 8px;
        }

        .peekaboot-back:hover {
            color: var(--pk-primary);
        }

        .peekaboot-tabs {
            display: flex;
            gap: 4px;
        }

        .peekaboot-tab {
            padding: 6px 12px;
            background: transparent;
            border: none;
            color: var(--pk-text-muted);
            cursor: pointer;
            border-radius: var(--pk-radius) var(--pk-radius) 0 0;
            font-size: 13px;
            font-family: var(--pk-font);
        }

        .peekaboot-tab:hover {
            color: var(--pk-text);
            background: var(--pk-bg-hover);
        }

        .peekaboot-tab.active {
            color: var(--pk-text);
            background: var(--pk-border);
        }

        .peekaboot-tab .count {
            margin-left: 4px;
            padding: 1px 6px;
            background: var(--pk-border);
            border-radius: 10px;
            font-size: 11px;
        }

        .peekaboot-tab.active .count {
            background: var(--pk-bg-hover);
        }

        .peekaboot-close {
            background: transparent;
            border: none;
            color: var(--pk-text-muted);
            cursor: pointer;
            padding: 4px 8px;
            font-size: 18px;
        }

        .peekaboot-close:hover {
            color: var(--pk-danger);
        }

        .peekaboot-content {
            flex: 1;
            overflow: auto;
            padding: 16px;
        }

        .peekaboot-loading {
            text-align: center;
            padding: 40px;
            color: var(--pk-text-muted);
        }

        /* Timeline styles */
        .span-item {
            margin: 4px 0;
            padding: 8px 12px;
            background: var(--pk-bg-alt);
            border-radius: var(--pk-radius);
            border-left: 3px solid var(--pk-border);
        }

        .span-item.has-error {
            border-left-color: var(--pk-danger);
        }

        .span-item.is-db {
            border-left-color: #a371f7;
        }

        .span-header {
            display: flex;
            align-items: center;
            gap: 8px;
            cursor: pointer;
            padding: 4px;
            margin: -4px;
            border-radius: var(--pk-radius);
            transition: background-color 0.15s ease;
        }

        .span-header:hover {
            background: var(--pk-bg-hover);
        }

        .span-icon {
            font-size: 14px;
        }

        .span-name {
            flex: 1;
            font-weight: 500;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            color: var(--pk-text-strong);
        }

        .span-duration {
            color: var(--pk-text-muted);
            font-size: 12px;
            font-family: var(--pk-font-mono);
        }

        .span-duration.slow {
            color: var(--pk-warning);
        }

        .span-duration.very-slow {
            color: var(--pk-danger);
        }

        .span-details {
            margin-top: 8px;
            padding-top: 8px;
            border-top: 1px solid var(--pk-border);
            font-size: 12px;
        }

        .span-tags {
            display: flex;
            flex-wrap: wrap;
            gap: 4px;
            margin-bottom: 8px;
        }

        .span-tag {
            padding: 2px 6px;
            background: var(--pk-border);
            border-radius: 3px;
            font-family: var(--pk-font-mono);
            font-size: 11px;
        }

        .span-children {
            margin-left: 20px;
            border-left: 1px dashed var(--pk-border);
            padding-left: 12px;
        }

        /* Log styles */
        .log-item {
            display: flex;
            gap: 12px;
            padding: 6px 12px;
            border-bottom: 1px solid var(--pk-bg-alt);
            font-family: var(--pk-font-mono);
            font-size: 12px;
        }

        .log-item:hover {
            background: var(--pk-bg-alt);
        }

        .log-time {
            color: var(--pk-text-muted);
            white-space: nowrap;
        }

        .log-level {
            width: 50px;
            font-weight: 600;
        }

        .log-level.DEBUG { color: var(--pk-text-muted); }
        .log-level.INFO { color: var(--pk-primary); }
        .log-level.WARN { color: var(--pk-warning); }
        .log-level.ERROR { color: var(--pk-danger); }

        .log-logger {
            color: #a371f7;
            width: 150px;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .log-message {
            flex: 1;
            word-break: break-word;
        }

        /* Query styles */
        .query-item {
            margin: 8px 0;
            padding: 12px;
            background: var(--pk-bg-alt);
            border-radius: var(--pk-radius);
            border-left: 3px solid #a371f7;
        }

        .query-header {
            display: flex;
            justify-content: space-between;
            margin-bottom: 8px;
        }

        .query-duration {
            font-weight: 600;
            font-family: var(--pk-font-mono);
        }

        .query-duration.slow { color: var(--pk-warning); }
        .query-duration.very-slow { color: var(--pk-danger); }

        .query-sql {
            font-family: var(--pk-font-mono);
            font-size: 12px;
            background: var(--pk-bg);
            padding: 8px;
            border-radius: var(--pk-radius);
            overflow-x: auto;
            white-space: pre-wrap;
            word-break: break-all;
        }

        .query-params {
            margin-top: 8px;
            font-size: 11px;
            color: var(--pk-text-muted);
        }

        /* Request styles */
        .request-section {
            margin-bottom: 16px;
        }

        .request-section h3 {
            font-size: 14px;
            margin: 0 0 8px 0;
            color: var(--pk-text-muted);
        }

        .request-table {
            width: 100%;
            border-collapse: collapse;
            font-size: 12px;
        }

        .request-table td {
            padding: 4px 8px;
            border-bottom: 1px solid var(--pk-bg-alt);
        }

        .request-table td:first-child {
            color: var(--pk-text-muted);
            width: 200px;
        }

        .request-table td:last-child {
            font-family: var(--pk-font-mono);
            word-break: break-all;
        }

        /* No data */
        .no-data {
            text-align: center;
            padding: 40px;
            color: var(--pk-text-muted);
        }

        /* Filter */
        .filter-bar {
            display: flex;
            gap: 12px;
            margin-bottom: 12px;
            align-items: center;
        }

        .filter-input {
            flex: 1;
            max-width: 300px;
            padding: 6px 10px;
            background: var(--pk-bg-alt);
            border: 1px solid var(--pk-border);
            border-radius: var(--pk-radius);
            color: var(--pk-text);
            font-size: 13px;
            font-family: var(--pk-font);
        }

        .filter-input:focus {
            outline: none;
            border-color: var(--pk-primary);
        }

        .filter-select {
            padding: 6px 10px;
            background: var(--pk-bg-alt);
            border: 1px solid var(--pk-border);
            border-radius: var(--pk-radius);
            color: var(--pk-text);
            font-size: 13px;
            font-family: var(--pk-font);
        }
    `;
    shadow.appendChild(expandedStyles);

    // Hide collapsed bar and show expanded
    const bar = shadow.querySelector('.peekaboot-bar');
    if (bar) bar.style.display = 'none';

    const expanded = document.createElement('div');
    expanded.className = 'peekaboot-expanded';
    expanded.innerHTML = `
        <div class="peekaboot-header">
            <button class="peekaboot-back" title="Back">&#8592;</button>
            <div class="peekaboot-tabs">
                <button class="peekaboot-tab" data-tab="request">Request</button>
                <button class="peekaboot-tab active" data-tab="spans">Spans</button>
                <button class="peekaboot-tab" data-tab="queries">Queries <span class="count">-</span></button>
                <button class="peekaboot-tab" data-tab="logs">Logs <span class="count">-</span></button>
            </div>
            <button class="peekaboot-close" title="Close">&times;</button>
        </div>
        <div class="peekaboot-content">
            <div class="peekaboot-loading">Loading trace data...</div>
        </div>
    `;
    shadow.appendChild(expanded);

    // Tab switching
    expanded.querySelectorAll('.peekaboot-tab').forEach(function(tab) {
        tab.addEventListener('click', function() {
            expanded.querySelectorAll('.peekaboot-tab').forEach(function(t) { t.classList.remove('active'); });
            tab.classList.add('active');
            activeTab = tab.dataset.tab;
            renderContent();
        });
    });

    // Close toolbar function (shared by button and ESC)
    function closeToolbar() {
        expanded.remove();
        if (bar) bar.style.display = 'flex';
        document.removeEventListener('keydown', escHandler);
    }

    function escHandler(e) {
        if (e.key === 'Escape') {
            closeToolbar();
        }
    }

    // Back and close buttons (both close the toolbar)
    expanded.querySelector('.peekaboot-back').addEventListener('click', closeToolbar);
    expanded.querySelector('.peekaboot-close').addEventListener('click', closeToolbar);

    // ESC key to close
    document.addEventListener('keydown', escHandler);

    // Fetch trace data from insights endpoint (includes logs from TraceDataStorage)
    if (data.traceId) {
        fetch(data.dashboardUrl + 'api/traces/' + data.traceId + '/insights')
            .then(function(r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function(trace) {
                // Convert TraceTree format to expected format for rendering
                traceData = convertTraceTree(trace);
                updateCounts();
                renderContent();
            })
            .catch(function(err) {
                expanded.querySelector('.peekaboot-content').innerHTML =
                    '<div class="no-data">Failed to load trace data: ' + err.message + '</div>';
            });
    } else {
        expanded.querySelector('.peekaboot-content').innerHTML =
            '<div class="no-data">No trace ID available</div>';
    }

    function updateCounts() {
        if (!traceData) return;
        const queries = countQueries(traceData.spans || []);
        const logs = (traceData.logs || []).length;
        expanded.querySelector('[data-tab="queries"] .count').textContent = queries;
        expanded.querySelector('[data-tab="logs"] .count').textContent = logs;
    }

    function countQueries(spans) {
        let count = 0;
        spans.forEach(function(span) {
            if (isDbSpan(span)) count++;
        });
        return count;
    }

    function isDbSpan(span) {
        const name = (span.name || '').toLowerCase();
        const tags = span.tags || {};
        return tags['db.system'] ||
               name.indexOf('query') === 0 ||
               name.indexOf('select') === 0 ||
               name.indexOf('insert') === 0 ||
               name.indexOf('update') === 0 ||
               name.indexOf('delete') === 0 ||
               name.indexOf('jdbc') >= 0 ||
               name.indexOf('sql') >= 0;
    }

    function renderContent() {
        const content = expanded.querySelector('.peekaboot-content');
        if (!traceData) {
            content.innerHTML = '<div class="peekaboot-loading">Loading...</div>';
            return;
        }

        switch (activeTab) {
            case 'request': renderRequest(content); break;
            case 'spans': renderSpans(content); break;
            case 'queries': renderQueries(content); break;
            case 'logs': renderLogs(content); break;
        }
    }

    function renderSpans(container) {
        const spans = traceData.spans || [];
        if (spans.length === 0) {
            container.innerHTML = '<div class="no-data">No spans recorded</div>';
            return;
        }

        let html = '<div class="spans-list">';
        const spanMap = {};
        let rootSpans = [];

        spans.forEach(function(span) {
            spanMap[span.spanId] = span;
        });

        spans.forEach(function(span) {
            if (!span.parentId || !spanMap[span.parentId]) {
                rootSpans.push(span);
            }
        });

        if (rootSpans.length === 0) rootSpans = spans;

        rootSpans.forEach(function(span) {
            html += renderSpanTree(span, spans, 0);
        });

        html += '</div>';
        container.innerHTML = html;

        // Add click handlers for expand/collapse
        container.querySelectorAll('.span-header').forEach(function(header) {
            header.addEventListener('click', function() {
                const details = header.nextElementSibling;
                if (details && details.classList.contains('span-details')) {
                    details.style.display = details.style.display === 'none' ? 'block' : 'none';
                }
            });
        });
    }

    function renderSpanTree(span, allSpans, depth) {
        const children = allSpans.filter(function(s) { return s.parentId === span.spanId; });
        const hasError = span.errorMessage || span.errorClass || (span.tags && span.tags.error === 'true');
        const isDb = isDbSpan(span);
        const duration = formatDuration(span.duration);
        const durationClass = getDurationClass(span.duration);

        const icon = isDb ? '&#128451;' : (hasError ? '&#9888;' : '&#9654;');
        const classes = ['span-item'];
        if (hasError) classes.push('has-error');
        if (isDb) classes.push('is-db');

        let html = '<div class="' + classes.join(' ') + '">';
        html += '<div class="span-header">';
        html += '<span class="span-icon">' + icon + '</span>';
        html += '<span class="span-name">' + escapeHtml(span.name || 'unknown') + '</span>';
        html += '<span class="span-duration ' + durationClass + '">' + duration + '</span>';
        html += '</div>';

        // Details (hidden by default)
        html += '<div class="span-details" style="display:none;">';
        if (span.tags && Object.keys(span.tags).length > 0) {
            html += '<div class="span-tags">';
            Object.keys(span.tags).forEach(function(key) {
                html += '<span class="span-tag">' + escapeHtml(key) + '=' + escapeHtml(String(span.tags[key])) + '</span>';
            });
            html += '</div>';
        }
        if (hasError) {
            html += '<div style="color:var(--pk-danger);">';
            if (span.errorClass) html += '<strong>' + escapeHtml(span.errorClass) + '</strong><br>';
            if (span.errorMessage) html += escapeHtml(span.errorMessage);
            html += '</div>';
        }
        html += '</div>';

        if (children.length > 0) {
            html += '<div class="span-children">';
            children.forEach(function(child) {
                html += renderSpanTree(child, allSpans, depth + 1);
            });
            html += '</div>';
        }

        html += '</div>';
        return html;
    }

    function renderQueries(container) {
        const spans = traceData.spans || [];
        const queries = spans.filter(isDbSpan);

        if (queries.length === 0) {
            container.innerHTML = '<div class="no-data">No database queries recorded</div>';
            return;
        }

        let html = '';
        queries.forEach(function(span) {
            const tags = span.tags || {};
            const sql = tags['db.statement'] || tags['db.query'] || span.name || 'Unknown query';
            const duration = formatDuration(span.duration);
            const durationClass = getDurationClass(span.duration);

            html += '<div class="query-item">';
            html += '<div class="query-header">';
            html += '<span>' + escapeHtml(tags['db.system'] || 'SQL') + '</span>';
            html += '<span class="query-duration ' + durationClass + '">' + duration + '</span>';
            html += '</div>';
            html += '<div class="query-sql">' + escapeHtml(sql) + '</div>';
            if (tags['db.parameters']) {
                html += '<div class="query-params">Parameters: ' + escapeHtml(tags['db.parameters']) + '</div>';
            }
            html += '</div>';
        });

        container.innerHTML = html;
    }

    function renderLogs(container) {
        const logs = traceData.logs || [];

        if (logs.length === 0) {
            container.innerHTML = '<div class="no-data">No logs recorded for this trace</div>';
            return;
        }

        let html = '<div class="filter-bar">';
        html += '<select class="filter-select" id="log-level-filter">';
        html += '<option value="">All Levels</option>';
        html += '<option value="ERROR">ERROR</option>';
        html += '<option value="WARN">WARN</option>';
        html += '<option value="INFO">INFO</option>';
        html += '<option value="DEBUG">DEBUG</option>';
        html += '</select>';
        html += '<input type="text" class="filter-input" id="log-filter" placeholder="Filter logs...">';
        html += '</div>';
        html += '<div id="log-list"></div>';

        container.innerHTML = html;
        renderLogList(logs, '', '');

        container.querySelector('#log-level-filter').addEventListener('change', function() {
            const filter = container.querySelector('#log-filter').value;
            renderLogList(logs, this.value, filter);
        });

        container.querySelector('#log-filter').addEventListener('input', function() {
            const level = container.querySelector('#log-level-filter').value;
            renderLogList(logs, level, this.value);
        });
    }

    function renderLogList(logs, levelFilter, textFilter) {
        const list = shadow.querySelector('#log-list');
        const filtered = logs.filter(function(log) {
            if (levelFilter && log.level !== levelFilter) return false;
            if (textFilter) {
                const search = textFilter.toLowerCase();
                return (log.message || '').toLowerCase().indexOf(search) >= 0 ||
                       (log.loggerName || '').toLowerCase().indexOf(search) >= 0;
            }
            return true;
        });

        if (filtered.length === 0) {
            list.innerHTML = '<div class="no-data">No logs match filter</div>';
            return;
        }

        let html = '';
        filtered.forEach(function(log) {
            const time = formatTime(log.timestamp);
            const logger = log.loggerName ? log.loggerName.split('.').pop() : '-';
            html += '<div class="log-item">';
            html += '<span class="log-time">' + time + '</span>';
            html += '<span class="log-level ' + (log.level || '') + '">' + (log.level || '-') + '</span>';
            html += '<span class="log-logger" title="' + escapeHtml(log.loggerName || '') + '">' + escapeHtml(logger) + '</span>';
            html += '<span class="log-message">' + escapeHtml(log.message || '') + '</span>';
            html += '</div>';
        });

        list.innerHTML = html;
    }

    function renderRequest(container) {
        let html = '';

        // Request info
        html += '<div class="request-section">';
        html += '<h3>Request</h3>';
        html += '<table class="request-table">';
        html += '<tr><td>Method</td><td>' + escapeHtml(data.method) + '</td></tr>';
        html += '<tr><td>Path</td><td>' + escapeHtml(data.path) + '</td></tr>';
        html += '<tr><td>Status</td><td>' + data.status + '</td></tr>';
        html += '<tr><td>Duration</td><td>' + data.duration + 'ms</td></tr>';
        html += '<tr><td>Trace ID</td><td style="font-family:var(--pk-font-mono);">' + escapeHtml(data.traceId || '-') + '</td></tr>';
        html += '</table>';
        html += '</div>';

        // Trace summary
        if (traceData) {
            html += '<div class="request-section">';
            html += '<h3>Trace Summary</h3>';
            html += '<table class="request-table">';
            html += '<tr><td>Total Spans</td><td>' + (traceData.spanCount || (traceData.spans || []).length) + '</td></tr>';
            html += '<tr><td>DB Queries</td><td>' + countQueries(traceData.spans || []) + '</td></tr>';
            html += '<tr><td>Logs</td><td>' + (traceData.logs || []).length + '</td></tr>';
            if (traceData.startTime) {
                html += '<tr><td>Start Time</td><td>' + formatDateTime(traceData.startTime) + '</td></tr>';
            }
            html += '</table>';
            html += '</div>';
        }

        container.innerHTML = html;
    }

    function formatDuration(duration) {
        if (!duration) return '-';
        let ms;
        if (typeof duration === 'object' && duration.seconds !== undefined) {
            ms = duration.seconds * 1000 + (duration.nano || 0) / 1000000;
        } else if (typeof duration === 'string') {
            const match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?/);
            if (match) {
                const h = parseFloat(match[1] || 0);
                const m = parseFloat(match[2] || 0);
                const s = parseFloat(match[3] || 0);
                ms = (h * 3600 + m * 60 + s) * 1000;
            } else {
                ms = parseFloat(duration);
            }
        } else {
            ms = duration;
        }
        if (isNaN(ms)) return '-';
        if (ms < 1) return '<1ms';
        if (ms < 1000) return Math.round(ms) + 'ms';
        return (ms / 1000).toFixed(2) + 's';
    }

    function getDurationClass(duration) {
        const ms = parseDurationMs(duration);
        if (ms > 500) return 'very-slow';
        if (ms > 100) return 'slow';
        return '';
    }

    function parseDurationMs(duration) {
        if (!duration) return 0;
        if (typeof duration === 'object' && duration.seconds !== undefined) {
            return duration.seconds * 1000 + (duration.nano || 0) / 1000000;
        }
        if (typeof duration === 'string') {
            const match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?/);
            if (match) {
                const h = parseFloat(match[1] || 0);
                const m = parseFloat(match[2] || 0);
                const s = parseFloat(match[3] || 0);
                return (h * 3600 + m * 60 + s) * 1000;
            }
            return parseFloat(duration) || 0;
        }
        return duration || 0;
    }

    function getDateFormatOptions(includeDate) {
        const locale = localStorage.getItem('peekaboot-locale') || navigator.language || 'en-US';

        const options = {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit'
        };

        if (includeDate) {
            options.year = 'numeric';
            options.month = 'short';
            options.day = 'numeric';
        }

        return { options: options, locale: locale };
    }

    function formatTime(timestamp) {
        if (!timestamp) return '-';
        const fmt = getDateFormatOptions(false);
        return new Date(timestamp).toLocaleTimeString(fmt.locale, fmt.options);
    }

    function formatDateTime(timestamp) {
        if (!timestamp) return '-';
        const fmt = getDateFormatOptions(true);
        return new Date(timestamp).toLocaleString(fmt.locale, fmt.options);
    }

    function escapeHtml(text) {
        if (text == null) return '';
        return String(text).replace(/[&<>"']/g, c =>
            ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
    }

    // Convert TraceTree format (from /insights endpoint) to flat spans array format
    function convertTraceTree(tree) {
        const spans = [];
        if (tree.rootSpan) {
            flattenSpanNode(tree.rootSpan, null, spans);
        }
        return {
            traceId: tree.traceId,
            startTime: tree.startTimeMs ? new Date(tree.startTimeMs).toISOString() : null,
            duration: tree.durationMs,
            spanCount: spans.length,
            spans: spans,
            logs: tree.logs || []
        };
    }

    function flattenSpanNode(node, parentId, spans) {
        const span = {
            spanId: node.spanId,
            parentId: parentId,
            name: node.name,
            kind: node.kind,
            startTime: node.startTimeMs ? new Date(node.startTimeMs).toISOString() : null,
            duration: node.durationMs,
            tags: node.tags || {},
            errorMessage: node.status === 'ERROR' ? (node.tags && node.tags['error.message']) : null,
            errorClass: node.status === 'ERROR' ? (node.tags && node.tags['error.type']) : null
        };
        spans.push(span);

        if (node.children && node.children.length > 0) {
            node.children.forEach(function(child) {
                flattenSpanNode(child, node.spanId, spans);
            });
        }
    }
})();
