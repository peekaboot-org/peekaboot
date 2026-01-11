(function() {
    'use strict';

    const API_ENDPOINT = '/peekaboot/api/actuator/all/insights';
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
            const response = await fetch('/peekaboot/api/traces/insights?limit=50');
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

        const traces = tracesData?.traces;
        if (!traces || traces.length === 0) {
            const noTracesMsg = features.traceCaptureMode === 'ALL'
                ? 'No traces recorded'
                : 'No error traces recorded';
            noTracesEl.querySelector('p').textContent = noTracesMsg;
            noTracesEl.classList.remove('hidden');
            return;
        }

        noTracesEl.classList.add('hidden');

        traces.forEach(trace => {
            listEl.appendChild(renderTraceItem(trace));
        });
    }

    function renderTraceItem(trace) {
        const item = document.createElement('div');
        item.className = 'trace-item';

        const traceIdShort = trace.traceId ? trace.traceId.substring(0, 16) + '...' : 'unknown';
        const startTime = trace.startTimeMs ? formatDate(new Date(trace.startTimeMs).toISOString()) : '-';
        const duration = formatDurationMs(trace.durationMs);
        const spanCount = trace.metrics?.totalSpans || 0;
        const hasErrors = trace.status === 'HAS_ERRORS';
        const hasSlow = trace.status === 'HAS_SLOW_SPANS';

        let statusBadge = '';
        if (hasErrors) {
            statusBadge = '<span class="trace-badge error">ERROR</span>';
        } else if (hasSlow) {
            statusBadge = '<span class="trace-badge warning">SLOW</span>';
        }

        item.innerHTML = `
            <div class="trace-header">
                <span class="trace-expand">&#9654;</span>
                <code class="trace-id">${escapeHtml(traceIdShort)}</code>
                <span class="trace-time">${startTime}</span>
                <span class="trace-duration">${duration}</span>
                <span class="trace-badge">${spanCount} spans</span>
                ${statusBadge}
            </div>
            <div class="trace-details">
                <div class="span-tree">
                    ${renderSpanNode(trace.rootSpan, 0)}
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

    function renderSpanNode(span, depth) {
        if (!span) {
            return '<p class="no-data">No spans</p>';
        }

        let html = renderSpanItem(span, depth);

        if (span.children && span.children.length > 0) {
            span.children.forEach(child => {
                html += renderSpanNode(child, depth + 1);
            });
        }

        return html;
    }

    function renderSpanItem(span, depth) {
        const indent = depth * 24;
        const hasError = span.status === 'ERROR' || (span.issues && span.issues.some(i => i.type === 'ERROR'));
        const errorClass = hasError ? 'error' : '';

        const duration = formatDurationMs(span.durationMs);
        const durationClass = getDurationClassMs(span.durationMs);
        const kind = span.kind || 'INTERNAL';
        const name = span.name || 'unknown';

        let detailsHtml = '';

        if (span.attributes && Object.keys(span.attributes).length > 0) {
            const tagsHtml = Object.entries(span.attributes)
                .map(([k, v]) => `<span class="span-tag"><strong>${escapeHtml(k)}:</strong> ${escapeHtml(String(v))}</span>`)
                .join('');
            detailsHtml += `<div class="span-tags">${tagsHtml}</div>`;
        }

        if (span.issues && span.issues.length > 0) {
            const issuesHtml = span.issues.map(issue => {
                const issueClass = issue.severity === 'error' ? 'error' : (issue.severity === 'warning' ? 'warning' : '');
                return `<span class="span-issue ${issueClass}">${escapeHtml(issue.type)}: ${escapeHtml(issue.message)}</span>`;
            }).join('');
            detailsHtml += `<div class="span-issues" style="margin-top: 8px;">${issuesHtml}</div>`;
        }

        let issueBadges = '';
        if (span.issues && span.issues.length > 0) {
            issueBadges = span.issues.map(issue => {
                if (issue.type === 'ERROR') {
                    return '<span class="trace-badge error">ERROR</span>';
                } else if (issue.type === 'SLOW' || issue.type === 'VERY_SLOW') {
                    return '<span class="trace-badge warning">SLOW</span>';
                } else if (issue.type === 'SLOW_QUERY') {
                    return '<span class="trace-badge warning">SLOW QUERY</span>';
                }
                return '';
            }).join('');
        }

        return `
            <div class="span-item ${errorClass}" style="margin-left: ${indent}px;">
                <div class="span-header">
                    <span class="span-name">${escapeHtml(name)}</span>
                    <span class="span-badge">${kind}</span>
                    <span class="span-duration ${durationClass}">${duration}</span>
                    ${issueBadges}
                </div>
                ${detailsHtml ? `<div class="span-tags">${detailsHtml}</div>` : ''}
            </div>
        `;
    }

    function getDurationClassMs(ms) {
        if (!ms) return '';
        if (ms > 500) return 'very-slow';
        if (ms > 100) return 'slow';
        return '';
    }

    function formatDurationMs(ms) {
        if (ms === null || ms === undefined) return '-';
        if (ms < 1) return '<1ms';
        if (ms < 1000) return Math.round(ms) + 'ms';
        if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
        return (ms / 60000).toFixed(2) + 'm';
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
        renderScheduledTasksTab();
    }

    function renderDashboardTab() {
        const { application, runtime, dataSources, health } = peekabootData;

        renderBuildInfo(application?.build);
        renderGitInfo(application?.git);
        renderSpringInfo(application);
        renderJavaInfo(application);
        renderOsInfo(runtime?.os);
        renderDataSourcesInfo(dataSources);
        renderMemoryInfo(runtime);
        renderHealthBanner(health);
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

    function renderSpringInfo(application) {
        const container = document.getElementById('spring-info');
        if (!container) return;
        container.innerHTML = '';

        if (!application) {
            container.innerHTML = '<p class="no-data">No Spring info available</p>';
            return;
        }

        if (application.springBootVersion) container.appendChild(createInfoRow('Boot', application.springBootVersion));
        if (application.springFrameworkVersion) container.appendChild(createInfoRow('Framework', application.springFrameworkVersion));
    }

    function renderDataSourcesInfo(dataSources) {
        const container = document.getElementById('datasources-container');
        if (!container) return;
        container.innerHTML = '';

        if (!dataSources || dataSources.length === 0) {
            container.classList.add('hidden');
            return;
        }

        container.classList.remove('hidden');

        dataSources.forEach((ds, index) => {
            const card = document.createElement('div');
            card.className = 'card datasource-card';
            card.dataset.dsIndex = index;

            const hostsStr = ds.hosts && ds.hosts.length > 0
                ? ds.hosts.map(h => h.host + (h.port ? ':' + h.port : '')).join(', ')
                : 'unknown';

            const isUp = ds.health === 'UP';
            const healthBadge = ds.health
                ? `<span class="ds-health-badge ${isUp ? 'is-up' : 'is-down'}">${escapeHtml(ds.health)}</span>`
                : '';

            const dbProduct = ds.databaseProduct
                ? (ds.databaseProduct.displayName || ds.databaseProduct)
                : '-';

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
                    <div class="info-row"><span class="info-label">Product</span><span class="info-value">${escapeHtml(String(dbProduct))}</span></div>
                    <div class="info-row"><span class="info-label">Driver</span><span class="info-value">${escapeHtml(ds.driver || '-')}</span></div>
                    ${Object.keys(ds.properties || {}).length > 0 ? '<div class="ds-params-toggle"><button class="btn btn-small">Show Connection Params</button></div>' : ''}
                </div>
                ${renderConnectionParams(ds.properties)}
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

        let html = '<div class="ds-params hidden"><div class="ds-params-list">';

        Object.entries(params).forEach(([key, value]) => {
            const isSensitive = /password|secret|key|token|credential/i.test(key);
            const displayValue = isSensitive ? '********' : value;
            html += `<div class="ds-param-item">
                <span class="ds-param-key">${escapeHtml(key)}</span>
                <span class="ds-param-value ${isSensitive ? 'sensitive' : ''}">${escapeHtml(displayValue || '-')}</span>
            </div>`;
        });

        html += '</div></div>';
        return html;
    }

    function renderJavaInfo(application) {
        const container = document.getElementById('java-info');
        container.innerHTML = '';

        if (!application) {
            container.innerHTML = '<p class="no-data">No Java info available</p>';
            return;
        }

        if (application.javaVersion) container.appendChild(createInfoRow('Version', application.javaVersion));
        if (application.javaVendor) container.appendChild(createInfoRow('Vendor', application.javaVendor));
    }

    function renderOsInfo(os) {
        const container = document.getElementById('os-info');
        container.innerHTML = '';

        if (!os) {
            container.innerHTML = '<p class="no-data">No system info available</p>';
            return;
        }

        if (os.name) container.appendChild(createInfoRow('OS', `${os.name} ${os.version || ''}`));
        if (os.arch) container.appendChild(createInfoRow('Architecture', os.arch));
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

        const status = health.status || 'UNKNOWN';
        statusText.textContent = status;

        const isUp = status === 'UP';
        const isDown = status === 'DOWN';

        dot.className = 'health-dot' + (isDown ? ' is-down' : (!isUp ? ' is-unknown' : ''));
        banner.className = 'health-banner clickable' + (isDown ? ' is-down' : (!isUp ? ' is-unknown' : ''));

        if (health.components && health.components.length > 0) {
            const total = health.components.length;
            const healthy = health.components.filter(c => c.status === 'UP').length;
            summary.textContent = `${healthy}/${total} healthy`;
        } else {
            summary.textContent = '';
        }

        banner.onclick = () => {
            const isExpanded = !healthComponents.classList.contains('hidden');
            healthComponents.classList.toggle('hidden');
            if (expandIcon) {
                expandIcon.textContent = isExpanded ? '\u25B6' : '\u25BC';
            }
        };
    }

    function renderMemoryInfo(runtime) {
        const container = document.getElementById('memory-info');
        const processInfo = document.getElementById('process-info');
        container.innerHTML = '';

        const memory = runtime?.memory;
        const storage = runtime?.storage;

        if (!memory && (!storage || storage.length === 0)) {
            container.innerHTML = '<p class="no-data">No memory info available</p>';
            processInfo.textContent = '';
            return;
        }

        processInfo.textContent = '';

        if (memory) {
            container.appendChild(createMemoryRowSimple('Heap', memory.heapUsed, memory.heapMax, memory.heapUsedPercent));

            if (memory.nonHeapUsed) {
                const nonHeapRow = document.createElement('div');
                nonHeapRow.className = 'memory-section';
                nonHeapRow.innerHTML = `
                    <div class="memory-header">
                        <span class="memory-label">Non-Heap</span>
                        <span class="memory-value">${formatBytes(memory.nonHeapUsed)}</span>
                    </div>
                `;
                container.appendChild(nonHeapRow);
            }
        }

        if (storage && storage.length > 0) {
            storage.forEach(s => {
                const used = s.total - s.free;
                container.appendChild(createStorageRowSimple(s.path || 'Disk', used, s.total, s.free, s.usedPercent));
            });
        }
    }

    function createMemoryRowSimple(name, used, max, percent) {
        const row = document.createElement('div');
        row.className = 'memory-section';

        const hasMax = max && max > 0;
        const percentage = percent || (hasMax ? (used / max) * 100 : 0);
        const fillClass = percentage >= 90 ? 'danger' : (percentage >= 70 ? 'warning' : '');

        row.innerHTML = `
            <div class="memory-header">
                <span class="memory-label">${name}</span>
                <span class="memory-value">${formatBytes(used)} / ${formatBytes(max)} (${percentage.toFixed(1)}%)</span>
            </div>
            <div class="memory-bar">
                <div class="memory-fill ${fillClass}" style="width: ${Math.min(percentage, 100)}%"></div>
            </div>
        `;

        return row;
    }

    function createStorageRowSimple(name, used, total, free, percent) {
        const row = document.createElement('div');
        row.className = 'memory-section storage-section';

        const percentage = percent || (total > 0 ? (used / total) * 100 : 0);
        const fillClass = percentage >= 90 ? 'warning' : '';

        row.innerHTML = `
            <div class="memory-header">
                <span class="memory-label">${escapeHtml(name)}</span>
                <span class="memory-value">${formatBytes(used)} used / ${formatBytes(total)} total (${formatBytes(free)} free)</span>
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

        if (!components || components.length === 0) {
            container.innerHTML = '<p class="no-data">No health components available</p>';
            return;
        }

        components.forEach(component => {
            const card = document.createElement('div');
            card.className = 'component-card';

            const status = component.status || 'UNKNOWN';
            const isUp = status === 'UP';
            const isDown = status === 'DOWN';
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
                    <span class="component-name">${escapeHtml(component.name)}</span>
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
        const env = peekabootData?.environment;
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
            const properties = source.properties || [];

            if (properties.length === 0) return;

            const filteredProperties = properties.filter(prop => {
                return matchesFilter(prop.key, prop.value, filterQuery);
            });

            if (filteredProperties.length === 0) return;

            totalMatches += filteredProperties.length;

            const sourceEl = document.createElement('div');
            sourceEl.className = 'property-source';

            const headerEl = document.createElement('div');
            headerEl.className = 'property-header';
            headerEl.innerHTML = `
                <span class="property-name">${highlightText(sourceName, filterQuery)}</span>
                <span class="property-count">${filteredProperties.length} properties</span>
            `;

            const listEl = document.createElement('div');
            listEl.className = 'property-list collapsed';

            filteredProperties.forEach(prop => {
                const item = document.createElement('div');
                item.className = 'property-item';
                item.innerHTML = `
                    <span class="property-key">${highlightText(prop.key, filterQuery)}</span>
                    <span class="property-value">${highlightText(formatValue(prop.value), filterQuery)}</span>
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

        const migrations = flyway?.migrations;
        if (!migrations || migrations.length === 0) {
            if (noFlywayEl) noFlywayEl.classList.remove('hidden');
            return;
        }

        if (noFlywayEl) noFlywayEl.classList.add('hidden');
        if (flywayTab) flywayTab.classList.remove('hidden');

        migrations.forEach(migration => {
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
    }

    // Loggers Tab
    function renderLoggersTab(filterQuery = '') {
        const loggersInfo = peekabootData?.loggers;
        const container = document.getElementById('loggers-list');
        const loggersTab = document.querySelector('[data-tab="loggers"]');

        if (!container) return;
        container.innerHTML = '';

        const packages = loggersInfo?.packages;
        if (!packages || packages.length === 0) {
            container.innerHTML = '<p class="no-data">No loggers available</p>';
            return;
        }

        if (loggersTab) loggersTab.classList.remove('hidden');

        const configuredOnly = document.getElementById('loggers-configured-only')?.checked || false;

        // Show summary
        const summaryEl = document.createElement('div');
        summaryEl.className = 'loggers-summary';
        summaryEl.innerHTML = `<span class="level-badge">Total: ${loggersInfo.totalCount}</span> <span class="level-badge">Configured: ${loggersInfo.configuredCount}</span>`;
        container.appendChild(summaryEl);

        let hasMatches = false;

        packages.forEach(group => {
            const filteredLoggers = group.loggers.filter(logger => {
                if (configuredOnly && !logger.configuredLevel) return false;
                if (filterQuery && !logger.name.toLowerCase().includes(filterQuery.toLowerCase())) return false;
                return true;
            });

            if (filteredLoggers.length === 0) return;
            hasMatches = true;

            const groupEl = document.createElement('div');
            groupEl.className = 'logger-group';

            const headerEl = document.createElement('div');
            headerEl.className = 'logger-group-header collapsed';
            headerEl.innerHTML = `
                <span class="logger-group-name">${highlightText(group.packageName, filterQuery)}</span>
                <span class="logger-group-count">${filteredLoggers.length} loggers</span>
            `;

            const listEl = document.createElement('div');
            listEl.className = 'logger-group-list collapsed';

            filteredLoggers.forEach(logger => {
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

        if (!hasMatches && filterQuery) {
            container.innerHTML = `<p class="no-data">No loggers matching criteria</p>`;
        }
    }

    // Config Tab
    function renderConfigTab(filterQuery = '') {
        const configInfo = peekabootData?.config;
        const container = document.getElementById('config-groups');
        const configTab = document.querySelector('[data-tab="config"]');

        if (!container) return;
        container.innerHTML = '';

        const groups = configInfo?.groups;
        if (!groups || groups.length === 0) {
            container.innerHTML = '<p class="no-data">No configuration properties available</p>';
            return;
        }

        if (configTab) configTab.classList.remove('hidden');

        let hasProps = false;

        groups.forEach(group => {
            const filteredProps = group.properties.filter(prop => {
                if (!filterQuery) return true;
                const matchesKey = prop.key.toLowerCase().includes(filterQuery.toLowerCase());
                const matchesValue = prop.value && prop.value.toLowerCase().includes(filterQuery.toLowerCase());
                return matchesKey || matchesValue;
            });

            if (filteredProps.length === 0) return;
            hasProps = true;

            const groupEl = document.createElement('div');
            groupEl.className = 'config-group';

            const headerEl = document.createElement('div');
            headerEl.className = 'config-header collapsed';
            headerEl.innerHTML = `
                <span class="config-prefix">${highlightText(group.prefix, filterQuery)}</span>
                <span class="config-count">${filteredProps.length} properties</span>
            `;

            const listEl = document.createElement('div');
            listEl.className = 'config-list collapsed';

            filteredProps.forEach(prop => {
                const item = document.createElement('div');
                item.className = 'config-item';

                const isSensitive = /password|secret|key|token|credential/i.test(prop.key);

                item.innerHTML = `
                    <span class="config-key">${highlightText(prop.key, filterQuery)}</span>
                    <span class="config-value ${isSensitive ? 'sensitive' : ''}">${highlightText(prop.value || '-', filterQuery)}</span>
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

        if (!hasProps) {
            container.innerHTML = filterQuery
                ? `<p class="no-data">No properties matching "${escapeHtml(filterQuery)}"</p>`
                : '<p class="no-data">No configuration properties available</p>';
        }
    }

    // Scheduled Tasks Tab
    function renderScheduledTasksTab() {
        const scheduledTasks = peekabootData?.scheduledTasks;
        const summaryEl = document.getElementById('scheduled-tasks-summary');
        const groupsEl = document.getElementById('scheduled-tasks-groups');
        const noTasksEl = document.getElementById('no-scheduled-tasks');
        const tabBtn = document.querySelector('[data-tab="scheduled-tasks"]');

        if (!summaryEl || !groupsEl) return;
        summaryEl.innerHTML = '';
        groupsEl.innerHTML = '';

        const tasks = scheduledTasks?.tasks;
        if (!tasks || tasks.length === 0) {
            if (noTasksEl) noTasksEl.classList.remove('hidden');
            return;
        }

        if (noTasksEl) noTasksEl.classList.add('hidden');
        if (tabBtn) tabBtn.classList.remove('hidden');

        // Summary badges
        summaryEl.innerHTML = `
            <span class="level-badge">Total: ${tasks.length}</span>
            <span class="level-badge">Cron: ${scheduledTasks.cronCount}</span>
            <span class="level-badge">Fixed Delay: ${scheduledTasks.fixedDelayCount}</span>
            <span class="level-badge">Fixed Rate: ${scheduledTasks.fixedRateCount}</span>
        `;

        // Group by type
        const tasksByType = {
            'CRON': tasks.filter(t => t.type === 'CRON'),
            'FIXED_DELAY': tasks.filter(t => t.type === 'FIXED_DELAY'),
            'FIXED_RATE': tasks.filter(t => t.type === 'FIXED_RATE')
        };

        const typeLabels = {
            'CRON': 'Cron Tasks',
            'FIXED_DELAY': 'Fixed Delay Tasks',
            'FIXED_RATE': 'Fixed Rate Tasks'
        };

        Object.entries(tasksByType).forEach(([type, typeTasks]) => {
            if (typeTasks.length === 0) return;

            const groupEl = document.createElement('div');
            groupEl.className = 'logger-group';

            const headerEl = document.createElement('div');
            headerEl.className = 'logger-group-header collapsed';
            headerEl.innerHTML = `
                <span class="logger-group-name">${typeLabels[type]}</span>
                <span class="logger-group-count">${typeTasks.length} tasks</span>
            `;

            const listEl = document.createElement('div');
            listEl.className = 'logger-group-list collapsed';

            typeTasks.forEach(task => {
                const item = document.createElement('div');
                item.className = 'scheduled-task-item';

                const statusClass = getTaskStatusClass(task.lastStatus);
                const targetShort = task.target.includes('.')
                    ? task.target.split('.').slice(-2).join('.')
                    : task.target;

                const typeLabel = type === 'CRON' ? 'Cron' : (type === 'FIXED_DELAY' ? 'Fixed Delay' : 'Fixed Rate');
                const scheduleDisplay = type === 'CRON'
                    ? interpretCronExpression(task.schedule)
                    : formatFixedInterval(task.intervalMs);

                item.innerHTML = `
                    <div class="task-row">
                        <div class="task-left">
                            <span class="task-type-badge ${type.toLowerCase()}">${typeLabel}</span>
                            <span class="task-schedule-value" title="${escapeHtml(task.schedule)}">${escapeHtml(scheduleDisplay)}</span>
                        </div>
                        <div class="task-right">
                            <span class="task-timing-item"><span class="task-timing-label">Last:</span> ${task.lastExecution ? formatDate(task.lastExecution) : 'Never'}</span>
                            <span class="task-timing-item"><span class="task-timing-label">Next:</span> ${task.nextExecution ? formatDate(task.nextExecution) : '-'}</span>
                            <span class="task-status ${statusClass}">${escapeHtml(task.lastStatus || 'PENDING')}</span>
                        </div>
                    </div>
                    <div class="task-target-row">
                        <span class="task-target" title="${escapeHtml(task.target)}">${escapeHtml(targetShort)}</span>
                    </div>
                    ${task.lastException ? `<div class="task-exception">${escapeHtml(task.lastException)}</div>` : ''}
                `;

                listEl.appendChild(item);
            });

            headerEl.addEventListener('click', () => {
                listEl.classList.toggle('collapsed');
                headerEl.classList.toggle('collapsed');
            });

            groupEl.appendChild(headerEl);
            groupEl.appendChild(listEl);
            groupsEl.appendChild(groupEl);
        });
    }

    function getTaskStatusClass(status) {
        if (!status) return '';
        switch (status.toUpperCase()) {
            case 'SUCCESS': return 'status-success';
            case 'FAILED': case 'ERROR': return 'status-error';
            case 'PENDING': return 'status-pending';
            default: return '';
        }
    }

    function formatFixedInterval(ms) {
        if (!ms) return '-';
        if (ms < 1000) return `Every ${ms}ms`;
        if (ms < 60000) return `Every ${ms / 1000}s`;
        if (ms < 3600000) return `Every ${ms / 60000}m`;
        if (ms < 86400000) return `Every ${ms / 3600000}h`;
        return `Every ${ms / 86400000}d`;
    }

    function interpretCronExpression(cron) {
        if (!cron) return '-';
        const parts = cron.trim().split(/\s+/);
        if (parts.length < 6) return cron;

        const [sec, min, hour, dayOfMonth, month, dayOfWeek] = parts;

        // Common patterns
        if (sec === '0' && min === '0' && hour === '0' && dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
            return 'Every day at midnight';
        }
        if (sec === '0' && min === '0' && hour === '*' && dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
            return 'Every hour';
        }
        if (sec === '0' && min === '*' && hour === '*' && dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
            return 'Every minute';
        }
        if (sec === '*' && min === '*' && hour === '*' && dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
            return 'Every second';
        }

        // Every N seconds/minutes/hours
        if (sec.startsWith('*/') && min === '*' && hour === '*') {
            return `Every ${sec.slice(2)} seconds`;
        }
        if (sec === '0' && min.startsWith('*/') && hour === '*') {
            return `Every ${min.slice(2)} minutes`;
        }
        if (sec === '0' && min === '0' && hour.startsWith('*/')) {
            return `Every ${hour.slice(2)} hours`;
        }

        // At specific minute each hour
        if (sec === '0' && /^\d+$/.test(min) && hour === '*' && dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
            return `Every hour at :${min.padStart(2, '0')}`;
        }

        // At specific time daily
        if (sec === '0' && /^\d+$/.test(min) && /^\d+$/.test(hour) && dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
            return `Daily at ${hour.padStart(2, '0')}:${min.padStart(2, '0')}`;
        }

        // Weekday patterns
        if (dayOfWeek !== '*' && dayOfWeek !== '?') {
            const days = { '0': 'Sun', '1': 'Mon', '2': 'Tue', '3': 'Wed', '4': 'Thu', '5': 'Fri', '6': 'Sat', '7': 'Sun' };
            const dayNames = dayOfWeek.split(',').map(d => days[d] || d).join(', ');
            if (/^\d+$/.test(min) && /^\d+$/.test(hour)) {
                return `${dayNames} at ${hour.padStart(2, '0')}:${min.padStart(2, '0')}`;
            }
        }

        // Day of month patterns
        if (/^\d+$/.test(dayOfMonth) && dayOfMonth !== '*') {
            if (/^\d+$/.test(min) && /^\d+$/.test(hour)) {
                return `Day ${dayOfMonth} at ${hour.padStart(2, '0')}:${min.padStart(2, '0')}`;
            }
        }

        return cron;
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

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
