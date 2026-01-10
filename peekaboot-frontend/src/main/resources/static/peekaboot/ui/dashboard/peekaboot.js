(function() {
    'use strict';

    const API_ENDPOINT = '/peekaboot/api/v1';
    const REFRESH_INTERVAL = 30000;

    let peekabootData = null;
    let refreshTimer = null;
    let isPaused = false;
    let features = { tracing: false, traceCaptureMode: 'ERRORS_ONLY', devToolbar: false };
    let tracesData = null;
    let tracesLoaded = false;

    async function init() {
        initTheme();
        initTabs();
        initRefreshControls();
        initEnvironmentFilter();
        initLoggersFilter();
        initConfigFilter();
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
                    tracesTab.classList.remove('hidden');
                    const tabLabel = features.traceCaptureMode === 'ALL' ? 'Traces' : 'Error Traces';
                    tracesTab.textContent = tabLabel;
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

        loadingEl.classList.remove('hidden');
        listEl.innerHTML = '';
        noTracesEl.classList.add('hidden');

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
            listEl.innerHTML = `<div class="error-banner"><span class="message">Failed to load traces: ${error.message}</span></div>`;
        } finally {
            loadingEl.classList.add('hidden');
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
            noTracesEl.classList.remove('hidden');
            return;
        }

        noTracesEl.classList.add('hidden');

        tracesData.forEach(trace => {
            listEl.appendChild(renderTraceItem(trace));
        });
    }

    function renderTraceItem(trace) {
        const item = document.createElement('div');
        item.className = 'trace-item';

        const traceIdShort = trace.traceId ? trace.traceId.substring(0, 16) + '...' : 'unknown';
        const startTime = trace.startTime ? formatDate(trace.startTime) : '-';
        const duration = trace.duration ? formatDuration(trace.duration) : '-';
        const spanCount = trace.spanCount || 0;

        item.innerHTML = `
            <div class="trace-header">
                <span class="trace-expand">&#9654;</span>
                <code class="trace-id">${escapeHtml(traceIdShort)}</code>
                <span class="trace-time">${startTime}</span>
                <span class="trace-duration">${duration}</span>
                <span class="trace-badge">${spanCount} spans</span>
            </div>
            <div class="trace-details">
                <div class="span-tree">
                    ${renderSpanTree(trace.spans)}
                </div>
            </div>
        `;

        const header = item.querySelector('.trace-header');
        const details = item.querySelector('.trace-details');
        const icon = item.querySelector('.trace-expand');

        header.addEventListener('click', () => {
            const isOpen = details.classList.contains('open');
            details.classList.toggle('open', !isOpen);
            icon.classList.toggle('open', !isOpen);
            icon.innerHTML = isOpen ? '&#9654;' : '&#9660;';
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
        const errorClass = hasError ? 'error' : '';

        const duration = formatDuration(span.duration);
        const durationClass = getDurationClass(span.duration);
        const kind = span.kind || 'INTERNAL';
        const name = span.name || 'unknown';

        let detailsHtml = '';

        if (span.tags && Object.keys(span.tags).length > 0) {
            const tagsHtml = Object.entries(span.tags)
                .map(([k, v]) => `<span class="span-tag"><strong>${escapeHtml(k)}:</strong> ${escapeHtml(String(v))}</span>`)
                .join('');
            detailsHtml += `<div class="span-tags">${tagsHtml}</div>`;
        }

        if (hasError) {
            detailsHtml += `<div style="color: var(--pk-danger); margin-top: 8px;">`;
            if (span.errorClass) {
                detailsHtml += `<strong>${escapeHtml(span.errorClass)}</strong><br>`;
            }
            if (span.errorMessage) {
                detailsHtml += `${escapeHtml(span.errorMessage)}`;
            }
            detailsHtml += `</div>`;
        }

        return `
            <div class="span-item ${errorClass}" style="margin-left: ${indent}px;">
                <div class="span-header">
                    <span class="span-name">${escapeHtml(name)}</span>
                    <span class="span-badge">${kind}</span>
                    <span class="span-duration ${durationClass}">${duration}</span>
                    ${hasError ? '<span class="trace-badge error">ERROR</span>' : ''}
                </div>
                ${detailsHtml ? `<div class="span-tags">${detailsHtml}</div>` : ''}
            </div>
        `;
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
        const tabButtons = document.querySelectorAll('#main-tabs .tab');
        const tabContents = document.querySelectorAll('.tab-content');

        tabButtons.forEach(button => {
            button.addEventListener('click', (e) => {
                e.preventDefault();
                const tabName = button.dataset.tab;

                tabButtons.forEach(tab => tab.classList.remove('active'));
                tabContents.forEach(content => content.classList.remove('active'));

                button.classList.add('active');
                document.getElementById(`${tabName}-tab`).classList.add('active');

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
        } else {
            pauseIcon.innerHTML = '&#10074;&#10074;';
            pauseBtn.title = 'Pause auto-refresh';
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

    function initLoggersFilter() {
        const filterInput = document.getElementById('loggers-filter');
        if (filterInput) {
            filterInput.addEventListener('input', (e) => {
                renderLoggersTab(e.target.value.trim());
            });
        }
        const checkbox = document.getElementById('loggers-configured-only');
        if (checkbox) {
            checkbox.addEventListener('change', () => {
                const filter = document.getElementById('loggers-filter')?.value || '';
                renderLoggersTab(filter);
            });
        }
    }

    function initConfigFilter() {
        const filterInput = document.getElementById('config-filter');
        if (filterInput) {
            filterInput.addEventListener('input', (e) => {
                renderConfigTab(e.target.value.trim());
            });
        }
    }

    function initErrorClose() {
        document.getElementById('error-close').addEventListener('click', () => {
            document.getElementById('error').classList.add('hidden');
        });
    }

    async function fetchData() {
        const loadingEl = document.getElementById('loading');
        const errorEl = document.getElementById('error');
        const refreshIcon = document.getElementById('refresh-icon');

        refreshIcon.classList.add('spinning');

        try {
            if (!peekabootData) {
                loadingEl.classList.remove('hidden');
            }
            errorEl.classList.add('hidden');

            const response = await fetch(API_ENDPOINT);

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            peekabootData = await response.json();
            renderData();
            updateLastUpdated();

            loadingEl.classList.add('hidden');
        } catch (error) {
            console.error('Error fetching data:', error);
            loadingEl.classList.add('hidden');
            errorEl.classList.remove('hidden');
            errorEl.querySelector('.message').textContent = `Failed to load data: ${error.message}`;
        } finally {
            refreshIcon.classList.remove('spinning');
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
        renderFlywayTab();
        renderLoggersTab();
        renderConfigTab();
    }

    function renderDashboardTab() {
        const { info, health, spring, dataSources } = peekabootData;
        const healthBody = health?.body || health;
        const healthComponents = healthBody?.components || {};

        renderBuildInfo(info?.build);
        renderGitInfo(info?.git);
        renderSpringInfo(spring);
        renderJavaInfo(info?.java);
        renderOsInfo(info?.os, info?.process);
        renderDataSourcesInfo(dataSources, healthComponents.db);
        renderMemoryInfo(info?.process, healthComponents.diskSpace);
        renderHealthBanner(healthBody);
        renderHealthComponents(healthComponents);
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

    function renderSpringInfo(spring) {
        const container = document.getElementById('spring-info');
        if (!container) return;
        container.innerHTML = '';

        if (!spring) {
            container.innerHTML = '<p class="no-data">No Spring info available</p>';
            return;
        }

        if (spring.bootVersion) container.appendChild(createInfoRow('Boot', spring.bootVersion));
        if (spring.frameworkVersion) container.appendChild(createInfoRow('Framework', spring.frameworkVersion));
    }

    function renderDataSourcesInfo(dataSources, dbHealth) {
        const container = document.getElementById('datasources-container');
        if (!container) return;
        container.innerHTML = '';

        if (!dataSources || dataSources.length === 0) {
            container.classList.add('hidden');
            return;
        }

        container.classList.remove('hidden');

        // Extract DB health status
        let dbStatus = 'UNKNOWN';
        let dbDetails = null;
        if (dbHealth) {
            dbStatus = dbHealth.status;
            if (typeof dbStatus === 'object') {
                dbStatus = dbStatus.code || dbStatus.name || 'UNKNOWN';
            }
            dbDetails = dbHealth.details;
        }
        const isDbUp = String(dbStatus).toUpperCase() === 'UP';

        dataSources.forEach((ds, index) => {
            const card = document.createElement('div');
            card.className = 'card datasource-card';
            card.dataset.dsIndex = index;

            const hostsStr = ds.hosts && ds.hosts.length > 0
                ? ds.hosts.join(', ')
                : 'unknown';

            // Build health badge HTML
            const healthBadge = dbHealth
                ? `<span class="ds-health-badge ${isDbUp ? 'is-up' : 'is-down'}">${escapeHtml(String(dbStatus))}</span>`
                : '';

            card.innerHTML = `
                <div class="card-header">
                    <span class="icon">&#128450;</span>
                    <span>${escapeHtml(ds.name || 'DataSource')}</span>
                    ${healthBadge}
                </div>
                <div class="card-body">
                    <div class="info-row"><span class="info-label">Database</span><span class="info-value">${escapeHtml(ds.databaseName || '-')}</span></div>
                    <div class="info-row"><span class="info-label">Host</span><span class="info-value mono">${escapeHtml(hostsStr)}</span></div>
                    <div class="info-row"><span class="info-label">User</span><span class="info-value">${escapeHtml(ds.username || '-')}</span></div>
                    <div class="info-row"><span class="info-label">Product</span><span class="info-value">${escapeHtml(ds.productName || '-')}</span></div>
                    <div class="info-row"><span class="info-label">Version</span><span class="info-value mono">${escapeHtml(ds.productVersion || '-')}</span></div>
                    <div class="info-row"><span class="info-label">Driver</span><span class="info-value">${escapeHtml(ds.driverName || '-')} ${escapeHtml(ds.driverVersion || '')}</span></div>
                    ${Object.keys(ds.connectionParams || {}).length > 0 ? '<div class="ds-params-toggle"><button class="btn btn-small">Show Connection Params</button></div>' : ''}
                </div>
                ${renderConnectionParams(ds.connectionParams)}
            `;

            const toggleBtn = card.querySelector('.ds-params-toggle button');
            if (toggleBtn) {
                toggleBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const paramsEl = card.querySelector('.ds-params');
                    if (paramsEl) {
                        paramsEl.classList.toggle('hidden');
                        toggleBtn.textContent = paramsEl.classList.contains('hidden')
                            ? 'Show Connection Params'
                            : 'Hide Connection Params';
                    }
                });
            }

            container.appendChild(card);
        });
    }

    function renderConnectionParams(params) {
        if (!params || Object.keys(params).length === 0) return '';

        // Group params by source
        const bySource = {};
        Object.entries(params).forEach(([key, param]) => {
            const source = param.source || 'URL';
            if (!bySource[source]) bySource[source] = [];
            bySource[source].push({ key, value: param.value });
        });

        let html = '<div class="ds-params hidden">';

        Object.entries(bySource).forEach(([source, items]) => {
            html += `<div class="ds-params-group">
                <div class="ds-params-source">${escapeHtml(source)}</div>
                <div class="ds-params-list">`;
            items.forEach(({ key, value }) => {
                const isSensitive = /password|secret|key|token|credential/i.test(key);
                const displayValue = isSensitive ? '********' : value;
                html += `<div class="ds-param-item">
                    <span class="ds-param-key">${escapeHtml(key)}</span>
                    <span class="ds-param-value ${isSensitive ? 'sensitive' : ''}">${escapeHtml(displayValue || '-')}</span>
                </div>`;
            });
            html += '</div></div>';
        });

        html += '</div>';
        return html;
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
        const dot = document.getElementById('health-dot');
        const statusText = document.getElementById('health-status-text');
        const summary = document.getElementById('health-summary');
        const expandIcon = document.getElementById('health-expand');
        const healthComponents = document.getElementById('health-components');

        if (!health) {
            statusText.textContent = 'Unknown';
            dot.className = 'health-dot is-unknown';
            banner.className = 'health-banner is-unknown clickable';
            summary.textContent = '';
            return;
        }

        let status = health.status;
        if (typeof status === 'object' && status !== null) {
            status = status.code || status.name || 'UNKNOWN';
        }
        status = String(status || 'UNKNOWN');
        statusText.textContent = status;

        const isUp = status.toUpperCase() === 'UP';
        const isDown = status.toUpperCase() === 'DOWN';

        dot.className = 'health-dot' + (isDown ? ' is-down' : (!isUp ? ' is-unknown' : ''));
        banner.className = 'health-banner clickable' + (isDown ? ' is-down' : (!isUp ? ' is-unknown' : ''));

        if (health.components) {
            const total = Object.keys(health.components).length;
            const healthy = Object.values(health.components).filter(c => {
                const compStatus = typeof c.status === 'object' ? (c.status?.code || c.status?.name) : c.status;
                return String(compStatus).toUpperCase() === 'UP';
            }).length;
            summary.textContent = `${healthy}/${total} healthy`;
        } else {
            summary.textContent = '';
        }

        // Click handler to toggle health components
        banner.onclick = () => {
            const isExpanded = !healthComponents.classList.contains('hidden');
            healthComponents.classList.toggle('hidden');
            if (expandIcon) {
                expandIcon.textContent = isExpanded ? '▶' : '▼';
            }
        };
    }

    function renderMemoryInfo(process, diskHealth) {
        const container = document.getElementById('memory-info');
        const processInfo = document.getElementById('process-info');
        container.innerHTML = '';

        if (!process?.memory && !diskHealth) {
            container.innerHTML = '<p class="no-data">No memory info available</p>';
            processInfo.textContent = '';
            return;
        }

        if (process?.pid) {
            processInfo.textContent = `PID: ${process.pid}`;
        }

        const { heap, nonHeap, garbageCollectors } = process?.memory || {};

        if (heap) {
            container.appendChild(createMemoryRow('Heap', heap.used, heap.max, heap.committed));
        }

        if (nonHeap) {
            container.appendChild(createMemoryRow('Non-Heap', nonHeap.used, nonHeap.max, nonHeap.committed));
        }

        if (garbageCollectors && garbageCollectors.length > 0) {
            const gcInfo = document.createElement('div');
            gcInfo.className = 'gc-info';
            const gcText = garbageCollectors
                .filter(gc => gc.collectionCount > 0)
                .map(gc => `${gc.name}: ${gc.collectionCount}`)
                .join(' | ');
            if (gcText) {
                gcInfo.innerHTML = `<span class="gc-label">GC:</span> <span class="gc-value">${escapeHtml(gcText)}</span>`;
                container.appendChild(gcInfo);
            }
        }

        // Render disk health section
        if (diskHealth?.details) {
            const diskSection = document.createElement('div');
            diskSection.className = 'disk-section';

            const details = diskHealth.details;
            const total = details.total || 0;
            const free = details.free || 0;
            const used = total - free;
            const threshold = details.threshold || 0;

            let diskStatus = diskHealth.status;
            if (typeof diskStatus === 'object') {
                diskStatus = diskStatus.code || diskStatus.name || 'UNKNOWN';
            }
            const isUp = String(diskStatus).toUpperCase() === 'UP';

            diskSection.appendChild(createStorageRow('Disk', used, total, free, threshold, isUp));
            container.appendChild(diskSection);
        }
    }

    function createStorageRow(name, used, total, free, threshold, isUp) {
        const row = document.createElement('div');
        row.className = 'memory-section storage-section';

        const hasTotal = total && total > 0;
        const percentage = hasTotal ? (used / total) * 100 : 0;
        const fillClass = !isUp ? 'danger' : (percentage >= 90 ? 'warning' : '');
        const statusDot = `<span class="storage-status-dot ${isUp ? '' : 'is-down'}"></span>`;

        row.innerHTML = `
            <div class="memory-header">
                <span class="memory-label">${statusDot} ${name}</span>
                <span class="memory-value">${formatBytes(used)} used / ${formatBytes(total)} total (${formatBytes(free)} free)</span>
            </div>
            <div class="memory-bar">
                <div class="memory-fill ${fillClass}" style="width: ${Math.min(percentage, 100)}%"></div>
            </div>
        `;

        return row;
    }

    function createMemoryRow(name, used, max, committed) {
        const row = document.createElement('div');
        row.className = 'memory-section';

        const hasMax = max && max > 0;
        const percentage = hasMax ? (used / max) * 100 : (committed > 0 ? (used / committed) * 100 : 0);
        const displayMax = hasMax ? formatBytes(max) : (committed > 0 ? formatBytes(committed) : '-');
        const fillClass = percentage >= 90 ? 'danger' : (percentage >= 70 ? 'warning' : '');

        row.innerHTML = `
            <div class="memory-header">
                <span class="memory-label">${name}</span>
                <span class="memory-value">${formatBytes(used)} / ${displayMax}${hasMax ? ` (${percentage.toFixed(1)}%)` : ''}</span>
            </div>
            <div class="memory-bar">
                <div class="memory-fill ${fillClass}" style="width: ${Math.min(percentage, 100)}%"></div>
            </div>
        `;

        return row;
    }

    function renderHealthComponents(components) {
        const container = document.getElementById('components-grid');
        container.innerHTML = '';

        if (!components || Object.keys(components).length === 0) {
            container.innerHTML = '<p class="no-data">No health components available</p>';
            return;
        }

        Object.entries(components).forEach(([name, component]) => {
            const card = document.createElement('div');
            card.className = 'component-card';

            let status = component.status;
            if (typeof status === 'object' && status !== null) {
                status = status.code || status.name || 'UNKNOWN';
            }
            status = String(status || 'UNKNOWN');

            const isUp = status.toUpperCase() === 'UP';
            const isDown = status.toUpperCase() === 'DOWN';
            const statusClass = isDown ? 'is-down' : (!isUp ? 'is-unknown' : '');

            let detailsHtml = '';
            if (component.details && Object.keys(component.details).length > 0) {
                const detailsText = Object.entries(component.details)
                    .filter(([k, v]) => v !== null && v !== '' && (!Array.isArray(v) || v.length > 0))
                    .map(([k, v]) => {
                        if (k === 'total' || k === 'free' || k === 'threshold') {
                            return `${k}: ${formatBytes(v)}`;
                        }
                        return `${k}: ${formatDetailValue(v)}`;
                    })
                    .join('\n');
                if (detailsText) {
                    detailsHtml = `<div class="component-details">${escapeHtml(detailsText)}</div>`;
                }
            }

            card.innerHTML = `
                <div class="component-header">
                    <span class="component-name">${escapeHtml(name)}</span>
                    <span class="component-dot ${statusClass}"></span>
                </div>
                ${detailsHtml}
            `;

            container.appendChild(card);
        });
    }

    function formatDetailValue(value) {
        if (typeof value === 'boolean') return value ? 'Yes' : 'No';
        if (Array.isArray(value)) return value.length > 0 ? value.join(', ') : '-';
        if (typeof value === 'object') return JSON.stringify(value);
        return String(value);
    }

    // Environment Tab
    function renderEnvironmentTab(filterQuery = '') {
        const env = peekabootData?.env;
        const container = document.getElementById('property-sources');
        container.innerHTML = '';

        if (!env?.propertySources || env.propertySources.length === 0) {
            container.innerHTML = '<p class="no-data">No environment properties available</p>';
            return;
        }

        // Show active profiles at top
        if (env.activeProfiles && env.activeProfiles.length > 0) {
            const profilesEl = document.createElement('div');
            profilesEl.className = 'active-profiles';
            profilesEl.innerHTML = `<strong>Active Profiles:</strong> ${env.activeProfiles.map(p => `<span class="profile-badge">${escapeHtml(p)}</span>`).join(' ')}`;
            container.appendChild(profilesEl);
        }

        let totalMatches = 0;

        env.propertySources.forEach(source => {
            const sourceName = source.name || 'Unknown Source';
            const properties = source.properties || {};

            if (Object.keys(properties).length === 0) return;

            const filteredEntries = Object.entries(properties).filter(([key, valueObj]) => {
                const value = valueObj?.value !== undefined ? valueObj.value : valueObj;
                return matchesFilter(key, value, filterQuery);
            });

            if (filteredEntries.length === 0) return;

            totalMatches += filteredEntries.length;

            const sourceEl = document.createElement('div');
            sourceEl.className = 'property-source';

            const headerEl = document.createElement('div');
            headerEl.className = 'property-header';
            headerEl.innerHTML = `
                <span class="property-name">${highlightText(sourceName, filterQuery)}</span>
                <span class="property-count">${filteredEntries.length} properties</span>
            `;

            const listEl = document.createElement('div');
            listEl.className = 'property-list collapsed';

            filteredEntries.forEach(([key, valueObj]) => {
                const value = valueObj?.value !== undefined ? valueObj.value : valueObj;
                const item = document.createElement('div');
                item.className = 'property-item';
                item.innerHTML = `
                    <span class="property-key">${highlightText(key, filterQuery)}</span>
                    <span class="property-value">${highlightText(formatValue(value), filterQuery)}</span>
                `;
                listEl.appendChild(item);
            });

            headerEl.classList.add('collapsed');
            headerEl.addEventListener('click', () => {
                listEl.classList.toggle('collapsed');
                headerEl.classList.toggle('collapsed');
            });

            sourceEl.appendChild(headerEl);
            sourceEl.appendChild(listEl);
            container.appendChild(sourceEl);
        });

        if (totalMatches === 0 && filterQuery) {
            container.innerHTML = `<p class="no-data">No properties matching "${escapeHtml(filterQuery)}"</p>`;
        }
    }

    // Flyway Tab
    function renderFlywayTab() {
        const flyway = peekabootData?.flyway;
        const container = document.getElementById('flyway-timeline');
        const noFlywayEl = document.getElementById('no-flyway');
        const flywayTab = document.querySelector('[data-tab="flyway"]');

        if (!container) return;
        container.innerHTML = '';

        const contexts = flyway?.contexts;
        if (!contexts) {
            if (noFlywayEl) noFlywayEl.classList.remove('hidden');
            return;
        }

        if (noFlywayEl) noFlywayEl.classList.add('hidden');
        if (flywayTab) flywayTab.classList.remove('hidden');

        let hasMigrations = false;

        Object.entries(contexts).forEach(([contextName, contextData]) => {
            const flywayBeans = contextData?.flywayBeans;
            if (!flywayBeans) return;

            Object.entries(flywayBeans).forEach(([beanName, beanData]) => {
                const migrations = beanData?.migrations;
                if (!migrations || migrations.length === 0) return;

                hasMigrations = true;

                // Sort by version descending (newest first)
                const sortedMigrations = [...migrations].sort((a, b) => {
                    const vA = parseFloat(a.version) || 0;
                    const vB = parseFloat(b.version) || 0;
                    return vB - vA;
                });

                sortedMigrations.forEach(migration => {
                    const card = document.createElement('div');
                    card.className = 'flyway-card';

                    const isSuccess = migration.state === 'SUCCESS';
                    const isFailed = migration.state === 'FAILED';
                    const statusClass = isFailed ? 'error' : (isSuccess ? 'success' : '');
                    const isSlow = migration.executionTime > 100;

                    card.innerHTML = `
                        <div class="flyway-header">
                            <span class="flyway-version">V${escapeHtml(migration.version)}</span>
                            <span class="flyway-description">${escapeHtml(migration.description)}</span>
                            <span class="flyway-status ${statusClass}">${escapeHtml(migration.state)}</span>
                        </div>
                        <div class="flyway-details">
                            <span class="flyway-time ${isSlow ? 'slow' : ''}">&#9201; ${migration.executionTime}ms</span>
                            <span class="flyway-date">${formatDate(migration.installedOn)}</span>
                            <span class="flyway-type">${escapeHtml(migration.type)}</span>
                        </div>
                        <div class="flyway-script">${escapeHtml(migration.script)}</div>
                    `;

                    container.appendChild(card);
                });
            });
        });

        if (!hasMigrations) {
            if (noFlywayEl) noFlywayEl.classList.remove('hidden');
        }
    }

    // Loggers Tab
    function renderLoggersTab(filterQuery = '') {
        const loggers = peekabootData?.loggers;
        const container = document.getElementById('loggers-list');
        const loggersTab = document.querySelector('[data-tab="loggers"]');

        if (!container) return;
        container.innerHTML = '';

        const loggersMap = loggers?.loggers;
        if (!loggersMap || Object.keys(loggersMap).length === 0) {
            container.innerHTML = '<p class="no-data">No loggers available</p>';
            return;
        }

        if (loggersTab) loggersTab.classList.remove('hidden');

        const configuredOnly = document.getElementById('loggers-configured-only')?.checked || false;

        // Group loggers by top-level package
        const groups = new Map();

        Object.entries(loggersMap).forEach(([name, config]) => {
            if (configuredOnly && !config.configuredLevel) return;
            if (filterQuery && !name.toLowerCase().includes(filterQuery.toLowerCase())) return;

            const parts = name.split('.');
            const groupKey = parts.length > 1 ? parts[0] : 'ROOT';

            if (!groups.has(groupKey)) {
                groups.set(groupKey, []);
            }
            groups.get(groupKey).push({ name, ...config });
        });

        if (groups.size === 0) {
            container.innerHTML = `<p class="no-data">No loggers matching criteria</p>`;
            return;
        }

        // Show level summary
        const levels = loggers.levels || ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];
        const summaryEl = document.createElement('div');
        summaryEl.className = 'loggers-summary';
        summaryEl.innerHTML = levels.map(level => {
            const count = Object.values(loggersMap).filter(l => l.effectiveLevel === level).length;
            return count > 0 ? `<span class="level-badge level-${level.toLowerCase()}">${level}: ${count}</span>` : '';
        }).join('');
        container.appendChild(summaryEl);

        // Render groups
        const sortedGroups = [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));

        sortedGroups.forEach(([groupName, groupLoggers]) => {
            const groupEl = document.createElement('div');
            groupEl.className = 'logger-group';

            const headerEl = document.createElement('div');
            headerEl.className = 'logger-group-header collapsed';
            headerEl.innerHTML = `
                <span class="logger-group-name">${highlightText(groupName, filterQuery)}</span>
                <span class="logger-group-count">${groupLoggers.length} loggers</span>
            `;

            const listEl = document.createElement('div');
            listEl.className = 'logger-group-list collapsed';

            groupLoggers.sort((a, b) => a.name.localeCompare(b.name)).forEach(logger => {
                const item = document.createElement('div');
                item.className = 'logger-item';

                const levelClass = `level-${(logger.effectiveLevel || 'info').toLowerCase()}`;
                const isConfigured = logger.configuredLevel !== null;

                item.innerHTML = `
                    <span class="logger-name ${isConfigured ? 'configured' : ''}">${highlightText(logger.name, filterQuery)}</span>
                    <span class="logger-level ${levelClass}">${logger.effectiveLevel || '-'}</span>
                `;

                listEl.appendChild(item);
            });

            headerEl.addEventListener('click', () => {
                listEl.classList.toggle('collapsed');
                headerEl.classList.toggle('collapsed');
            });

            groupEl.appendChild(headerEl);
            groupEl.appendChild(listEl);
            container.appendChild(groupEl);
        });
    }

    // Config Tab
    function renderConfigTab(filterQuery = '') {
        const configprops = peekabootData?.configprops;
        const container = document.getElementById('config-groups');
        const configTab = document.querySelector('[data-tab="config"]');

        if (!container) return;
        container.innerHTML = '';

        const contexts = configprops?.contexts;
        if (!contexts) {
            container.innerHTML = '<p class="no-data">No configuration properties available</p>';
            return;
        }

        if (configTab) configTab.classList.remove('hidden');

        let hasProps = false;

        Object.entries(contexts).forEach(([contextName, contextData]) => {
            const beans = contextData?.beans;
            if (!beans || Object.keys(beans).length === 0) return;

            // Group by prefix
            const groups = new Map();

            Object.entries(beans).forEach(([beanName, beanData]) => {
                const prefix = beanData?.prefix || 'other';
                const properties = beanData?.properties || {};

                if (Object.keys(properties).length === 0) return;

                // Apply filter
                if (filterQuery) {
                    const matchesPrefix = prefix.toLowerCase().includes(filterQuery.toLowerCase());
                    const matchesProps = Object.entries(properties).some(([k, v]) =>
                        k.toLowerCase().includes(filterQuery.toLowerCase()) ||
                        String(v).toLowerCase().includes(filterQuery.toLowerCase())
                    );
                    if (!matchesPrefix && !matchesProps) return;
                }

                if (!groups.has(prefix)) {
                    groups.set(prefix, []);
                }
                groups.get(prefix).push({ beanName, properties });
            });

            if (groups.size === 0) return;
            hasProps = true;

            const sortedGroups = [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]));

            sortedGroups.forEach(([prefix, beans]) => {
                const groupEl = document.createElement('div');
                groupEl.className = 'config-group';

                const propCount = beans.reduce((sum, b) => sum + Object.keys(b.properties).length, 0);

                const headerEl = document.createElement('div');
                headerEl.className = 'config-header collapsed';
                headerEl.innerHTML = `
                    <span class="config-prefix">${highlightText(prefix, filterQuery)}</span>
                    <span class="config-count">${propCount} properties</span>
                `;

                const listEl = document.createElement('div');
                listEl.className = 'config-list collapsed';

                beans.forEach(({ properties }) => {
                    Object.entries(properties).forEach(([key, value]) => {
                        const item = document.createElement('div');
                        item.className = 'config-item';

                        // Mask sensitive values
                        const isSensitive = /password|secret|key|token|credential/i.test(key);
                        const displayValue = isSensitive ? '********' : formatValue(value);

                        item.innerHTML = `
                            <span class="config-key">${highlightText(key, filterQuery)}</span>
                            <span class="config-value ${isSensitive ? 'sensitive' : ''}">${highlightText(displayValue, filterQuery)}</span>
                        `;

                        listEl.appendChild(item);
                    });
                });

                headerEl.addEventListener('click', () => {
                    listEl.classList.toggle('collapsed');
                    headerEl.classList.toggle('collapsed');
                });

                groupEl.appendChild(headerEl);
                groupEl.appendChild(listEl);
                container.appendChild(groupEl);
            });
        });

        if (!hasProps) {
            container.innerHTML = filterQuery
                ? `<p class="no-data">No properties matching "${escapeHtml(filterQuery)}"</p>`
                : '<p class="no-data">No configuration properties available</p>';
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
            <span class="info-label">${escapeHtml(label)}</span>
            <span class="info-value${isMonospace ? ' mono' : ''}">${escapeHtml(String(value))}</span>
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
