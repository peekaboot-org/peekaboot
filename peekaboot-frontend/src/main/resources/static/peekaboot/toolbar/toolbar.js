(function() {
    'use strict';

    var host = document.getElementById('peekaboot-toolbar-host');
    if (!host || !host.shadowRoot) return;

    var shadow = host.shadowRoot;
    var data = JSON.parse(document.getElementById('peekaboot-toolbar-data').textContent);
    var traceData = null;
    var activeTab = 'timeline';

    // Add expanded view styles
    var expandedStyles = document.createElement('style');
    expandedStyles.textContent = `
        .peekaboot-expanded {
            position: fixed;
            bottom: 0;
            left: 0;
            right: 0;
            height: 50vh;
            min-height: 300px;
            max-height: 70vh;
            background: #1a1a2e;
            color: #c9d1d9;
            font: 13px/1.5 system-ui, -apple-system, sans-serif;
            display: flex;
            flex-direction: column;
            border-top: 2px solid #30363d;
            z-index: 2147483647;
        }
        .peekaboot-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 8px 16px;
            background: #161b22;
            border-bottom: 1px solid #30363d;
        }
        .peekaboot-tabs {
            display: flex;
            gap: 4px;
        }
        .peekaboot-tab {
            padding: 6px 12px;
            background: transparent;
            border: none;
            color: #8b949e;
            cursor: pointer;
            border-radius: 4px 4px 0 0;
            font-size: 13px;
        }
        .peekaboot-tab:hover {
            color: #c9d1d9;
            background: #21262d;
        }
        .peekaboot-tab.active {
            color: #c9d1d9;
            background: #30363d;
        }
        .peekaboot-tab .count {
            margin-left: 4px;
            padding: 1px 6px;
            background: #30363d;
            border-radius: 10px;
            font-size: 11px;
        }
        .peekaboot-tab.active .count {
            background: #484f58;
        }
        .peekaboot-close {
            background: transparent;
            border: none;
            color: #8b949e;
            cursor: pointer;
            padding: 4px 8px;
            font-size: 18px;
        }
        .peekaboot-close:hover {
            color: #f85149;
        }
        .peekaboot-content {
            flex: 1;
            overflow: auto;
            padding: 16px;
        }
        .peekaboot-loading {
            text-align: center;
            padding: 40px;
            color: #8b949e;
        }

        /* Timeline styles */
        .span-item {
            margin: 4px 0;
            padding: 8px 12px;
            background: #21262d;
            border-radius: 4px;
            border-left: 3px solid #30363d;
        }
        .span-item.has-error {
            border-left-color: #f85149;
        }
        .span-item.is-db {
            border-left-color: #a371f7;
        }
        .span-header {
            display: flex;
            align-items: center;
            gap: 8px;
            cursor: pointer;
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
        }
        .span-duration {
            color: #8b949e;
            font-size: 12px;
        }
        .span-duration.slow {
            color: #d29922;
        }
        .span-duration.very-slow {
            color: #f85149;
        }
        .span-details {
            margin-top: 8px;
            padding-top: 8px;
            border-top: 1px solid #30363d;
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
            background: #30363d;
            border-radius: 3px;
            font-family: monospace;
            font-size: 11px;
        }
        .span-children {
            margin-left: 20px;
            border-left: 1px dashed #30363d;
            padding-left: 12px;
        }

        /* Log styles */
        .log-item {
            display: flex;
            gap: 12px;
            padding: 6px 12px;
            border-bottom: 1px solid #21262d;
            font-family: monospace;
            font-size: 12px;
        }
        .log-item:hover {
            background: #21262d;
        }
        .log-time {
            color: #8b949e;
            white-space: nowrap;
        }
        .log-level {
            width: 50px;
            font-weight: 600;
        }
        .log-level.DEBUG { color: #8b949e; }
        .log-level.INFO { color: #58a6ff; }
        .log-level.WARN { color: #d29922; }
        .log-level.ERROR { color: #f85149; }
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
            background: #21262d;
            border-radius: 4px;
            border-left: 3px solid #a371f7;
        }
        .query-header {
            display: flex;
            justify-content: space-between;
            margin-bottom: 8px;
        }
        .query-duration {
            font-weight: 600;
        }
        .query-duration.slow { color: #d29922; }
        .query-duration.very-slow { color: #f85149; }
        .query-sql {
            font-family: monospace;
            font-size: 12px;
            background: #161b22;
            padding: 8px;
            border-radius: 4px;
            overflow-x: auto;
            white-space: pre-wrap;
            word-break: break-all;
        }
        .query-params {
            margin-top: 8px;
            font-size: 11px;
            color: #8b949e;
        }

        /* Request styles */
        .request-section {
            margin-bottom: 16px;
        }
        .request-section h3 {
            font-size: 14px;
            margin: 0 0 8px 0;
            color: #8b949e;
        }
        .request-table {
            width: 100%;
            border-collapse: collapse;
            font-size: 12px;
        }
        .request-table td {
            padding: 4px 8px;
            border-bottom: 1px solid #21262d;
        }
        .request-table td:first-child {
            color: #8b949e;
            width: 200px;
        }
        .request-table td:last-child {
            font-family: monospace;
            word-break: break-all;
        }

        /* No data */
        .no-data {
            text-align: center;
            padding: 40px;
            color: #8b949e;
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
            background: #21262d;
            border: 1px solid #30363d;
            border-radius: 4px;
            color: #c9d1d9;
            font-size: 13px;
        }
        .filter-input:focus {
            outline: none;
            border-color: #58a6ff;
        }
        .filter-select {
            padding: 6px 10px;
            background: #21262d;
            border: 1px solid #30363d;
            border-radius: 4px;
            color: #c9d1d9;
            font-size: 13px;
        }
    `;
    shadow.appendChild(expandedStyles);

    // Hide collapsed bar and show expanded
    var bar = shadow.querySelector('.peekaboot-bar');
    if (bar) bar.style.display = 'none';

    var expanded = document.createElement('div');
    expanded.className = 'peekaboot-expanded';
    expanded.innerHTML = `
        <div class="peekaboot-header">
            <div class="peekaboot-tabs">
                <button class="peekaboot-tab active" data-tab="timeline">Timeline</button>
                <button class="peekaboot-tab" data-tab="queries">Queries <span class="count">-</span></button>
                <button class="peekaboot-tab" data-tab="logs">Logs <span class="count">-</span></button>
                <button class="peekaboot-tab" data-tab="request">Request</button>
            </div>
            <button class="peekaboot-close" title="Close">×</button>
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

    // Close button
    expanded.querySelector('.peekaboot-close').addEventListener('click', function() {
        expanded.remove();
        if (bar) bar.style.display = 'flex';
    });

    // Fetch trace data
    if (data.traceId) {
        fetch(data.dashboardUrl + 'api/traces/' + data.traceId + '/details')
            .then(function(r) { return r.json(); })
            .catch(function() {
                return fetch(data.dashboardUrl + 'api/traces/' + data.traceId).then(function(r) { return r.json(); });
            })
            .then(function(trace) {
                traceData = trace;
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
        var queries = countQueries(traceData.spans || []);
        var logs = (traceData.logs || []).length;
        expanded.querySelector('[data-tab="queries"] .count').textContent = queries;
        expanded.querySelector('[data-tab="logs"] .count').textContent = logs;
    }

    function countQueries(spans) {
        var count = 0;
        spans.forEach(function(span) {
            if (isDbSpan(span)) count++;
        });
        return count;
    }

    function isDbSpan(span) {
        var name = (span.name || '').toLowerCase();
        var tags = span.tags || {};
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
        var content = expanded.querySelector('.peekaboot-content');
        if (!traceData) {
            content.innerHTML = '<div class="peekaboot-loading">Loading...</div>';
            return;
        }

        switch (activeTab) {
            case 'timeline': renderTimeline(content); break;
            case 'queries': renderQueries(content); break;
            case 'logs': renderLogs(content); break;
            case 'request': renderRequest(content); break;
        }
    }

    function renderTimeline(container) {
        var spans = traceData.spans || [];
        if (spans.length === 0) {
            container.innerHTML = '<div class="no-data">No spans recorded</div>';
            return;
        }

        var html = '<div class="timeline">';
        var spanMap = {};
        var rootSpans = [];

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
                var details = header.nextElementSibling;
                if (details && details.classList.contains('span-details')) {
                    details.style.display = details.style.display === 'none' ? 'block' : 'none';
                }
            });
        });
    }

    function renderSpanTree(span, allSpans, depth) {
        var children = allSpans.filter(function(s) { return s.parentId === span.spanId; });
        var hasError = span.errorMessage || span.errorClass || (span.tags && span.tags.error === 'true');
        var isDb = isDbSpan(span);
        var duration = formatDuration(span.duration);
        var durationClass = getDurationClass(span.duration);

        var icon = isDb ? '🗄' : (hasError ? '⚠' : '▸');
        var classes = ['span-item'];
        if (hasError) classes.push('has-error');
        if (isDb) classes.push('is-db');

        var html = '<div class="' + classes.join(' ') + '">';
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
            html += '<div style="color:#f85149;">';
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
        var spans = traceData.spans || [];
        var queries = spans.filter(isDbSpan);

        if (queries.length === 0) {
            container.innerHTML = '<div class="no-data">No database queries recorded</div>';
            return;
        }

        var html = '';
        queries.forEach(function(span) {
            var tags = span.tags || {};
            var sql = tags['db.statement'] || tags['db.query'] || span.name || 'Unknown query';
            var duration = formatDuration(span.duration);
            var durationClass = getDurationClass(span.duration);

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
        var logs = traceData.logs || [];

        if (logs.length === 0) {
            container.innerHTML = '<div class="no-data">No logs recorded for this trace</div>';
            return;
        }

        var html = '<div class="filter-bar">';
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
            var filter = container.querySelector('#log-filter').value;
            renderLogList(logs, this.value, filter);
        });

        container.querySelector('#log-filter').addEventListener('input', function() {
            var level = container.querySelector('#log-level-filter').value;
            renderLogList(logs, level, this.value);
        });
    }

    function renderLogList(logs, levelFilter, textFilter) {
        var list = shadow.querySelector('#log-list');
        var filtered = logs.filter(function(log) {
            if (levelFilter && log.level !== levelFilter) return false;
            if (textFilter) {
                var search = textFilter.toLowerCase();
                return (log.message || '').toLowerCase().indexOf(search) >= 0 ||
                       (log.loggerName || '').toLowerCase().indexOf(search) >= 0;
            }
            return true;
        });

        if (filtered.length === 0) {
            list.innerHTML = '<div class="no-data">No logs match filter</div>';
            return;
        }

        var html = '';
        filtered.forEach(function(log) {
            var time = log.timestamp ? new Date(log.timestamp).toLocaleTimeString() : '-';
            var logger = log.loggerName ? log.loggerName.split('.').pop() : '-';
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
        var html = '';

        // Request info
        html += '<div class="request-section">';
        html += '<h3>Request</h3>';
        html += '<table class="request-table">';
        html += '<tr><td>Method</td><td>' + escapeHtml(data.method) + '</td></tr>';
        html += '<tr><td>Path</td><td>' + escapeHtml(data.path) + '</td></tr>';
        html += '<tr><td>Status</td><td>' + data.status + '</td></tr>';
        html += '<tr><td>Duration</td><td>' + data.duration + 'ms</td></tr>';
        html += '<tr><td>Trace ID</td><td style="font-family:monospace;">' + escapeHtml(data.traceId || '-') + '</td></tr>';
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
                html += '<tr><td>Start Time</td><td>' + new Date(traceData.startTime).toLocaleString() + '</td></tr>';
            }
            html += '</table>';
            html += '</div>';
        }

        container.innerHTML = html;
    }

    function formatDuration(duration) {
        if (!duration) return '-';
        var ms;
        if (typeof duration === 'object' && duration.seconds !== undefined) {
            ms = duration.seconds * 1000 + (duration.nano || 0) / 1000000;
        } else if (typeof duration === 'string') {
            var match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?/);
            if (match) {
                var h = parseFloat(match[1] || 0);
                var m = parseFloat(match[2] || 0);
                var s = parseFloat(match[3] || 0);
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
        var ms = parseDurationMs(duration);
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
            var match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?/);
            if (match) {
                var h = parseFloat(match[1] || 0);
                var m = parseFloat(match[2] || 0);
                var s = parseFloat(match[3] || 0);
                return (h * 3600 + m * 60 + s) * 1000;
            }
            return parseFloat(duration) || 0;
        }
        return duration || 0;
    }

    function escapeHtml(text) {
        if (!text) return '';
        var div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
})();
