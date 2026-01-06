(function() {
    'use strict';

    const API_ENDPOINT = '/peekaboot/api';
    const REFRESH_INTERVAL = 30000;

    let peekabootData = null;
    let refreshTimer = null;
    let isPaused = false;
    let features = { tracing: false, traceCaptureMode: 'ERRORS_ONLY', debugToolbar: false };
    let tracesData = null;
    let tracesLoaded = false;

    async function init() {
        initTheme();
        initTabs();
        initRefreshControls();
        initEnvironmentFilter();
        initErrorClose();
        await fetchFeatures();
        fetchData();
    }

    async function fetchFeatures() {
        try {
            const response = await fetch('/peekaboot/api/features');
            if (response.ok) {
                features = await response.json();
                if (features.tracing) {
                    const tracesTab = document.querySelector('[data-tab="traces"]');
                    tracesTab.style.display = '';
                    const tabLabel = features.traceCaptureMode === 'ALL' ? 'Traces' : 'Error Traces';
                    tracesTab.querySelector('a').textContent = tabLabel;
                }
            }
        } catch (error) {
            console.warn('Could not fetch features:', error);
        }
    }

    async function fetchTraces() {
        const loadingEl = document.getElementById('traces-loading');
        const listEl = document.getElementById('traces-list');
        const noTracesEl = document.getElementById('no-traces');

        loadingEl.style.display = 'block';
        listEl.innerHTML = '';
        noTracesEl.style.display = 'none';

        try {
            const response = await fetch('/peekaboot/api/traces?limit=50');
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            tracesData = await response.json();
            tracesLoaded = true;
            renderTracesTab();
        } catch (error) {
            console.error('Error fetching traces:', error);
            listEl.innerHTML = `<div class="notification is-danger">Failed to load traces: ${error.message}</div>`;
        } finally {
            loadingEl.style.display = 'none';
        }
    }

    function renderTracesTab() {
        const listEl = document.getElementById('traces-list');
        const noTracesEl = document.getElementById('no-traces');
        listEl.innerHTML = '';

        if (!tracesData || tracesData.length === 0) {
            const noTracesMsg = features.traceCaptureMode === 'ALL'
                ? 'No traces recorded'
                : 'No error traces recorded';
            noTracesEl.querySelector('p').textContent = noTracesMsg;
            noTracesEl.style.display = 'block';
            return;
        }

        noTracesEl.style.display = 'none';

        tracesData.forEach(trace => {
            listEl.appendChild(renderTraceItem(trace));
        });
    }

    function renderTraceItem(trace) {
        const item = document.createElement('div');
        item.className = 'trace-item box mb-4';

        const traceIdShort = trace.traceId ? trace.traceId.substring(0, 16) + '...' : 'unknown';
        const startTime = trace.startTime ? formatDate(trace.startTime) : '-';
        const duration = trace.duration ? formatDuration(trace.duration) : '-';
        const spanCount = trace.spanCount || 0;

        item.innerHTML = `
            <div class="trace-header">
                <div class="trace-summary">
                    <span class="trace-expand-icon">&#9654;</span>
                    <code class="trace-id">${escapeHtml(traceIdShort)}</code>
                    <span class="trace-time">${startTime}</span>
                    <span class="trace-duration tag is-light">${duration}</span>
                    <span class="trace-span-count tag is-light">${spanCount} spans</span>
                </div>
            </div>
            <div class="trace-details" style="display: none;">
                <div class="span-tree">
                    ${renderSpanTree(trace.spans)}
                </div>
            </div>
        `;

        const header = item.querySelector('.trace-header');
        const details = item.querySelector('.trace-details');
        const icon = item.querySelector('.trace-expand-icon');

        header.addEventListener('click', () => {
            const isExpanded = details.style.display !== 'none';
            details.style.display = isExpanded ? 'none' : 'block';
            icon.innerHTML = isExpanded ? '&#9654;' : '&#9660;';
            item.classList.toggle('is-expanded', !isExpanded);
        });

        return item;
    }

    function renderSpanTree(spans) {
        if (!spans || spans.length === 0) {
            return '<p class="no-data">No spans</p>';
        }

        const spanMap = new Map();
        const rootSpans = [];

        spans.forEach(span => {
            spanMap.set(span.spanId, span);
        });

        spans.forEach(span => {
            if (!span.parentId || !spanMap.has(span.parentId)) {
                rootSpans.push(span);
            }
        });

        if (rootSpans.length === 0) {
            rootSpans.push(...spans);
        }

        function renderSpanWithChildren(span, depth) {
            const children = spans.filter(s => s.parentId === span.spanId);
            let html = renderSpanItem(span, depth);
            children.forEach(child => {
                html += renderSpanWithChildren(child, depth + 1);
            });
            return html;
        }

        return rootSpans.map(span => renderSpanWithChildren(span, 0)).join('');
    }

    function renderSpanItem(span, depth) {
        const indent = depth * 24;
        const hasError = span.errorMessage || span.errorClass;
        const errorClass = hasError ? 'span-error' : '';

        const duration = formatDuration(span.duration);
        const kind = span.kind || 'INTERNAL';
        const name = span.name || 'unknown';

        let detailsHtml = '';

        if (span.tags && Object.keys(span.tags).length > 0) {
            const tagsHtml = Object.entries(span.tags)
                .map(([k, v]) => `<span class="span-tag"><strong>${escapeHtml(k)}:</strong> ${escapeHtml(String(v))}</span>`)
                .join('');
            detailsHtml += `<div class="span-tags">${tagsHtml}</div>`;
        }

        if (span.events && span.events.length > 0) {
            const eventsHtml = span.events
                .map(e => `<span class="span-event">${escapeHtml(e.name)} @ ${formatDate(e.timestamp)}</span>`)
                .join('');
            detailsHtml += `<div class="span-events">${eventsHtml}</div>`;
        }

        if (hasError) {
            detailsHtml += `<div class="span-error-info">`;
            if (span.errorClass) {
                detailsHtml += `<div class="error-class">${escapeHtml(span.errorClass)}</div>`;
            }
            if (span.errorMessage) {
                detailsHtml += `<div class="error-message">${escapeHtml(span.errorMessage)}</div>`;
            }
            detailsHtml += `</div>`;
        }

        if (span.remoteServiceName || span.remoteIp) {
            const remote = [span.remoteServiceName, span.remoteIp, span.remotePort].filter(Boolean).join(':');
            detailsHtml += `<div class="span-remote">Remote: ${escapeHtml(remote)}</div>`;
        }

        return `
            <div class="span-item ${errorClass}" style="margin-left: ${indent}px;">
                <div class="span-header">
                    <span class="span-connector">${depth > 0 ? '└─' : ''}</span>
                    <span class="span-name">${escapeHtml(name)}</span>
                    <span class="span-kind tag is-small is-light">${kind}</span>
                    <span class="span-duration">${duration}</span>
                    ${hasError ? '<span class="span-error-badge">ERROR</span>' : ''}
                </div>
                ${detailsHtml ? `<div class="span-details">${detailsHtml}</div>` : ''}
            </div>
        `;
    }

    function initTheme() {
        const savedTheme = localStorage.getItem('peekaboot-theme') ||
            (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
        setTheme(savedTheme);

        document.getElementById('theme-toggle').addEventListener('click', () => {
            const currentTheme = document.documentElement.getAttribute('data-theme');
            const newTheme = currentTheme === 'light' ? 'dark' : 'light';
            setTheme(newTheme);
            localStorage.setItem('peekaboot-theme', newTheme);
        });
    }

    function setTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        document.getElementById('theme-icon').textContent = theme === 'light' ? '\u263E' : '\u2600';
    }

    function initTabs() {
        const tabItems = document.querySelectorAll('#main-tabs li');
        const tabContents = document.querySelectorAll('.tab-content');

        tabItems.forEach(item => {
            item.addEventListener('click', (e) => {
                e.preventDefault();
                const tabName = item.dataset.tab;

                tabItems.forEach(tab => tab.classList.remove('is-active'));
                tabContents.forEach(content => content.classList.remove('is-active'));

                item.classList.add('is-active');
                document.getElementById(`${tabName}-tab`).classList.add('is-active');

                if (tabName === 'traces' && !tracesLoaded) {
                    fetchTraces();
                }
            });
        });
    }

    function initRefreshControls() {
        document.getElementById('refresh-btn').addEventListener('click', () => {
            fetchData();
        });

        document.getElementById('pause-btn').addEventListener('click', () => {
            togglePause();
        });

        startAutoRefresh();
    }

    function startAutoRefresh() {
        if (refreshTimer) clearInterval(refreshTimer);
        refreshTimer = setInterval(() => {
            if (!isPaused) fetchData();
        }, REFRESH_INTERVAL);
    }

    function togglePause() {
        isPaused = !isPaused;
        const pauseIcon = document.getElementById('pause-icon');
        const pauseBtn = document.getElementById('pause-btn');

        if (isPaused) {
            pauseIcon.innerHTML = '&#9654;';
            pauseBtn.title = 'Resume auto-refresh';
            pauseBtn.classList.add('is-warning');
        } else {
            pauseIcon.innerHTML = '&#10074;&#10074;';
            pauseBtn.title = 'Pause auto-refresh';
            pauseBtn.classList.remove('is-warning');
        }
    }

    function initEnvironmentFilter() {
        const filterInput = document.getElementById('env-filter');
        if (filterInput) {
            filterInput.addEventListener('input', (e) => {
                renderEnvironmentTab(e.target.value.trim());
            });
        }
    }

    function initErrorClose() {
        document.getElementById('error-close').addEventListener('click', () => {
            document.getElementById('error').style.display = 'none';
        });
    }

    async function fetchData() {
        const loadingEl = document.getElementById('loading');
        const errorEl = document.getElementById('error');
        const refreshIcon = document.getElementById('refresh-icon');

        refreshIcon.classList.add('is-spinning');

        try {
            if (!peekabootData) {
                loadingEl.style.display = 'block';
            }
            errorEl.style.display = 'none';

            const response = await fetch(API_ENDPOINT);

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            peekabootData = await response.json();
            renderData();
            updateLastUpdated();

            loadingEl.style.display = 'none';
        } catch (error) {
            console.error('Error fetching data:', error);
            loadingEl.style.display = 'none';
            errorEl.style.display = 'block';
            errorEl.querySelector('.error-message').textContent = `Failed to load data: ${error.message}`;
        } finally {
            refreshIcon.classList.remove('is-spinning');
        }
    }

    function updateLastUpdated() {
        const now = new Date();
        const timeStr = now.toLocaleTimeString();
        document.getElementById('last-updated').textContent = `Updated ${timeStr}`;
    }

    function renderData() {
        if (!peekabootData) return;

        renderDashboardTab();
        renderEnvironmentTab();
    }

    function renderDashboardTab() {
        const { info, health } = peekabootData;

        renderBuildInfo(info?.build);
        renderGitInfo(info?.git);
        renderJavaInfo(info?.java);
        renderOsInfo(info?.os, info?.process);
        renderHealthBanner(health);
        renderMemoryInfo(info?.process);
        renderHealthComponents(health?.components);
    }

    function renderBuildInfo(build) {
        const container = document.getElementById('build-info');
        container.innerHTML = '';

        if (!build || Object.keys(build).length === 0) {
            container.innerHTML = '<p class="no-data">No build info available</p>';
            return;
        }

        if (build.name) container.appendChild(createInfoRow('Name', build.name));
        if (build.version) container.appendChild(createInfoRow('Version', build.version));
        if (build.group) container.appendChild(createInfoRow('Group', build.group));
        if (build.artifact) container.appendChild(createInfoRow('Artifact', build.artifact));
        if (build.time) container.appendChild(createInfoRow('Built', formatDate(build.time)));
    }

    function renderGitInfo(git) {
        const container = document.getElementById('git-info');
        container.innerHTML = '';

        if (!git || Object.keys(git).length === 0) {
            container.innerHTML = '<p class="no-data">No git info available</p>';
            return;
        }

        if (git.branch) container.appendChild(createInfoRow('Branch', git.branch));
        if (git.commit?.id) {
            const commitId = git.commit.id.abbrev || git.commit.id;
            container.appendChild(createInfoRow('Commit', commitId, true));
        }
        if (git.commit?.time) container.appendChild(createInfoRow('Commit Time', formatDate(git.commit.time)));
        if (git.dirty !== undefined) container.appendChild(createInfoRow('Dirty', git.dirty ? 'Yes' : 'No'));
    }

    function renderJavaInfo(java) {
        const container = document.getElementById('java-info');
        container.innerHTML = '';

        if (!java) {
            container.innerHTML = '<p class="no-data">No Java info available</p>';
            return;
        }

        if (java.version) container.appendChild(createInfoRow('Version', java.version));
        if (java.vendor?.name) container.appendChild(createInfoRow('Vendor', java.vendor.name));
        if (java.runtime?.name) container.appendChild(createInfoRow('Runtime', java.runtime.name));
        if (java.jvm?.name) container.appendChild(createInfoRow('JVM', java.jvm.name));
    }

    function renderOsInfo(os, process) {
        const container = document.getElementById('os-info');
        container.innerHTML = '';

        if (!os) {
            container.innerHTML = '<p class="no-data">No system info available</p>';
            return;
        }

        if (os.name) container.appendChild(createInfoRow('OS', `${os.name} ${os.version || ''}`));
        if (os.arch) container.appendChild(createInfoRow('Architecture', os.arch));
        if (process?.cpus) container.appendChild(createInfoRow('CPUs', process.cpus));
        if (process?.owner) container.appendChild(createInfoRow('User', process.owner));
    }

    function renderHealthBanner(health) {
        const banner = document.getElementById('health-banner');
        const indicator = document.getElementById('health-indicator');
        const statusText = document.getElementById('health-status-text');
        const summary = document.getElementById('health-summary');

        if (!health) {
            statusText.textContent = 'Unknown';
            indicator.className = 'health-indicator is-unknown';
            banner.className = 'box health-banner is-unknown';
            summary.textContent = '';
            return;
        }

        const status = health.status || 'UNKNOWN';
        statusText.textContent = status;

        const isUp = status.toUpperCase() === 'UP';
        const isDown = status.toUpperCase() === 'DOWN';

        indicator.className = 'health-indicator' + (isDown ? ' is-down' : (!isUp ? ' is-unknown' : ''));
        banner.className = 'box health-banner' + (isDown ? ' is-down' : (!isUp ? ' is-unknown' : ''));

        if (health.components) {
            const total = Object.keys(health.components).length;
            const healthy = Object.values(health.components).filter(c => c.status === 'UP').length;
            summary.textContent = `${healthy}/${total} healthy`;
        } else {
            summary.textContent = '';
        }
    }

    function renderMemoryInfo(process) {
        const container = document.getElementById('memory-info');
        const processInfo = document.getElementById('process-info');
        container.innerHTML = '';

        if (!process?.memory) {
            container.innerHTML = '<p class="no-data">No memory info available</p>';
            processInfo.textContent = '';
            return;
        }

        if (process.pid) {
            processInfo.textContent = `PID: ${process.pid}`;
        }

        const { heap, nonHeap } = process.memory;

        if (heap) {
            container.appendChild(createMemoryRow('Heap', heap.used, heap.max, heap.committed));
        }

        if (nonHeap) {
            container.appendChild(createMemoryRow('Non-Heap', nonHeap.used, nonHeap.max, nonHeap.committed));
        }
    }

    function createMemoryRow(name, used, max, committed) {
        const row = document.createElement('div');
        row.className = 'memory-row';

        const hasMax = max && max > 0;
        const percentage = hasMax ? (used / max) * 100 : (committed > 0 ? (used / committed) * 100 : 0);
        const displayMax = hasMax ? formatBytes(max) : (committed > 0 ? formatBytes(committed) : '-');

        row.innerHTML = `
            <div class="memory-label">
                <span class="memory-name">${name}</span>
                <span class="memory-value">${formatBytes(used)} / ${displayMax}${hasMax ? ` (${percentage.toFixed(1)}%)` : ''}</span>
            </div>
            <progress class="progress ${getProgressClass(percentage)}" value="${percentage}" max="100"></progress>
        `;

        return row;
    }

    function getProgressClass(percentage) {
        if (percentage >= 90) return 'is-danger';
        if (percentage >= 70) return 'is-warning';
        return 'is-success';
    }

    function renderHealthComponents(components) {
        const container = document.getElementById('components-grid');
        container.innerHTML = '';

        if (!components || Object.keys(components).length === 0) {
            container.innerHTML = '<div class="column"><p class="no-data">No health components available</p></div>';
            return;
        }

        Object.entries(components).forEach(([name, component]) => {
            const col = document.createElement('div');
            col.className = 'column is-one-quarter-desktop is-half-tablet';

            const status = component.status || 'UNKNOWN';
            const isUp = status.toUpperCase() === 'UP';
            const isDown = status.toUpperCase() === 'DOWN';
            const statusClass = isDown ? 'is-down' : (!isUp ? 'is-unknown' : '');

            let detailsHtml = '';
            if (component.details && Object.keys(component.details).length > 0) {
                const detailsText = Object.entries(component.details)
                    .filter(([k, v]) => v !== null && v !== '' && !Array.isArray(v) || (Array.isArray(v) && v.length > 0))
                    .map(([k, v]) => {
                        if (k === 'total' || k === 'free' || k === 'threshold') {
                            return `${k}: ${formatBytes(v)}`;
                        }
                        return `${k}: ${formatDetailValue(v)}`;
                    })
                    .join('\n');
                if (detailsText) {
                    detailsHtml = `<div class="component-details">${detailsText}</div>`;
                }
            }

            col.innerHTML = `
                <div class="component-card">
                    <div class="component-header">
                        <span class="component-name">${name}</span>
                        <div class="component-status">
                            <span class="status-dot ${statusClass}"></span>
                            <span class="is-size-7">${status}</span>
                        </div>
                    </div>
                    ${detailsHtml}
                </div>
            `;

            container.appendChild(col);
        });
    }

    function formatDetailValue(value) {
        if (typeof value === 'boolean') return value ? 'Yes' : 'No';
        if (Array.isArray(value)) return value.length > 0 ? value.join(', ') : '-';
        if (typeof value === 'object') return JSON.stringify(value);
        return String(value);
    }

    function renderEnvironmentTab(filterQuery = '') {
        const { environment } = peekabootData;
        const container = document.getElementById('property-sources');
        container.innerHTML = '';

        if (!environment || Object.keys(environment).length === 0) {
            container.innerHTML = '<p class="no-data">No environment properties available</p>';
            return;
        }

        let totalMatches = 0;

        Object.entries(environment).forEach(([sourceName, properties]) => {
            if (!properties || typeof properties !== 'object') return;

            const filteredEntries = Object.entries(properties).filter(([key, value]) =>
                matchesFilter(key, value, filterQuery)
            );

            if (filteredEntries.length === 0) return;

            totalMatches += filteredEntries.length;

            const sourceEl = document.createElement('div');
            sourceEl.className = 'property-source';

            const headerEl = document.createElement('div');
            headerEl.className = 'property-source-header';
            headerEl.innerHTML = `
                <span class="property-source-name">${highlightText(sourceName, filterQuery)}</span>
                <span class="property-source-count">${filteredEntries.length} properties</span>
            `;

            const contentEl = document.createElement('div');
            contentEl.className = 'property-source-content';

            filteredEntries.forEach(([key, value]) => {
                const item = document.createElement('div');
                item.className = 'property-item';
                item.innerHTML = `
                    <span class="property-key">${highlightText(key, filterQuery)}</span>
                    <span class="property-value">${highlightText(formatValue(value), filterQuery)}</span>
                `;
                contentEl.appendChild(item);
            });

            headerEl.addEventListener('click', () => {
                contentEl.classList.toggle('is-collapsed');
            });

            sourceEl.appendChild(headerEl);
            sourceEl.appendChild(contentEl);
            container.appendChild(sourceEl);
        });

        if (totalMatches === 0 && filterQuery) {
            container.innerHTML = `<p class="no-data">No properties matching "${filterQuery}"</p>`;
        }
    }

    function matchesFilter(key, value, filter) {
        if (!filter) return true;
        const filterLower = filter.toLowerCase();
        return key.toLowerCase().includes(filterLower) ||
               String(value).toLowerCase().includes(filterLower);
    }

    function highlightText(text, query) {
        if (!query) return escapeHtml(text);

        const textStr = String(text);
        const lowerText = textStr.toLowerCase();
        const lowerQuery = query.toLowerCase();

        let result = '';
        let lastIndex = 0;
        let index = lowerText.indexOf(lowerQuery);

        while (index !== -1) {
            result += escapeHtml(textStr.substring(lastIndex, index));
            result += `<mark>${escapeHtml(textStr.substring(index, index + query.length))}</mark>`;
            lastIndex = index + query.length;
            index = lowerText.indexOf(lowerQuery, lastIndex);
        }

        result += escapeHtml(textStr.substring(lastIndex));
        return result;
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    function createInfoRow(label, value, isMonospace = false) {
        const row = document.createElement('div');
        row.className = 'info-row';
        row.innerHTML = `
            <span class="info-label">${label}</span>
            <span class="info-value${isMonospace ? ' monospace' : ''}">${escapeHtml(String(value))}</span>
        `;
        return row;
    }

    function formatValue(value) {
        if (value === null || value === undefined) return '-';
        if (typeof value === 'object') return JSON.stringify(value);
        return String(value);
    }

    function formatBytes(bytes) {
        if (bytes === null || bytes === undefined || bytes < 0) return '-';
        if (bytes === 0) return '0 B';

        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        const size = i < sizes.length ? sizes[i] : sizes[sizes.length - 1];
        const value = bytes / Math.pow(k, Math.min(i, sizes.length - 1));

        return value.toFixed(value >= 100 ? 0 : (value >= 10 ? 1 : 2)) + ' ' + size;
    }

    function formatDate(dateStr) {
        if (!dateStr) return '-';
        try {
            const date = new Date(dateStr);
            return date.toLocaleDateString(undefined, {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit'
            });
        } catch {
            return dateStr;
        }
    }

    function formatDuration(duration) {
        if (!duration) return '-';

        let ms;
        if (typeof duration === 'string') {
            const match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?/);
            if (match) {
                const hours = parseFloat(match[1] || 0);
                const minutes = parseFloat(match[2] || 0);
                const seconds = parseFloat(match[3] || 0);
                ms = (hours * 3600 + minutes * 60 + seconds) * 1000;
            } else {
                ms = parseFloat(duration);
            }
        } else if (typeof duration === 'object' && duration.seconds !== undefined) {
            ms = duration.seconds * 1000 + (duration.nano || 0) / 1000000;
        } else {
            ms = duration;
        }

        if (isNaN(ms)) return '-';

        if (ms < 1) return '<1ms';
        if (ms < 1000) return Math.round(ms) + 'ms';
        if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
        return (ms / 60000).toFixed(2) + 'm';
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
