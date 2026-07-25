(function() {
    'use strict';

    const API_ENDPOINT = '/peekaboot/api/actuator/all/insights';
    const REFRESH_INTERVAL = 30000;

    let peekabootData = null;
    let refreshTimer = null;
    let isPaused = false;
    let features = { tracing: false, metrics: false, traceCaptureMode: 'ERRORS_ONLY', devToolbar: false };
    let tracesData = null;
    let tracesLoaded = false;
    let metricsData = null;
    let metricsLoaded = false;
    let selectedRootActionTypes = new Set(['HTTP_REQUEST', 'SCHEDULED_JOB',
        'MESSAGE_CONSUMER', 'DATABASE', 'INTERNAL', 'RPC_CALL', 'UNKNOWN']);

    let currentLocale = navigator.language || 'en-US';
    let useServerTimezone = false;  // Default: browser timezone
    let serverTimezone = null;      // Will be populated from API response

    // Root action type display mappings
    const ROOT_ACTION_ICONS = {
        HTTP_REQUEST: '&#127760;',     // Globe
        SCHEDULED_JOB: '&#128337;',    // Clock
        MESSAGE_CONSUMER: '&#128233;', // Envelope
        RPC_CALL: '&#128279;',         // Link
        DATABASE: '&#128450;',         // Filing cabinet
        INTERNAL: '&#9881;',           // Gear
        UNKNOWN: '&#10067;'            // Question mark
    };

    const ROOT_ACTION_LABELS = {
        HTTP_REQUEST: 'HTTP Request',
        SCHEDULED_JOB: 'Scheduled Job',
        MESSAGE_CONSUMER: 'Message Consumer',
        RPC_CALL: 'RPC Call',
        DATABASE: 'Database',
        INTERNAL: 'Internal',
        UNKNOWN: 'Unknown'
    };

    // Deep-linking support
    function parseHash() {
        const hash = window.location.hash.slice(1); // remove #
        if (!hash) return { tab: 'dashboard', detail: null };
        const parts = hash.split('/');
        return {
            tab: parts[0] || 'dashboard',
            detail: parts[1] || null
        };
    }

    function setHash(tab, detail = null) {
        const hash = detail ? `#${tab}/${detail}` : `#${tab}`;
        if (window.location.hash !== hash) {
            history.pushState(null, '', hash);
        }
    }

    function handleHashChange() {
        const { tab, detail } = parseHash();
        activateTab(tab);
        if (tab === 'traces' && detail) {
            expandTraceById(detail);
        }
    }

    function expandTraceById(traceId) {
        // Validate traceId to prevent selector injection
        if (!traceId || !/^[a-zA-Z0-9_-]+$/.test(traceId)) {
            console.warn('Invalid trace ID:', traceId);
            return;
        }

        // Open the trace detail overlay
        if (window.PeekabootTraceDetail) {
            PeekabootTraceDetail.open(traceId);
        }
    }

    // Current filter for root operation (e.g., scheduler target)
    let currentRootOperationFilter = null;

    function navigateToTracesWithFilter(actionType, rootOperation) {
        // Set filter to only show the specified action type
        selectedRootActionTypes.clear();
        selectedRootActionTypes.add(actionType);

        // Set root operation filter (e.g., scheduler target)
        currentRootOperationFilter = rootOperation || null;

        // Update checkboxes to reflect new filter state
        document.querySelectorAll('#traces-filter input').forEach(cb => {
            cb.checked = cb.value === actionType;
        });

        // Navigate to traces tab
        activateTab('traces');
        setHash('traces');

        // Refresh the traces view with new filters from backend
        tracesLoaded = false;
        fetchTraces();
    }

    function navigateToScheduledTasks() {
        activateTab('scheduled-tasks');
        setHash('scheduled-tasks');
    }

    async function init() {
        initTheme();
        initTabs();
        initRefreshControls();
        initTimezoneControls();
        initLocaleSelector();
        initEnvironmentFilter();
        initLoggersFilter();
        initConfigFilter();
        initTracesFilter();
        initMetricsFilter();
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
                if (features.metrics) {
                    const metricsTab = document.querySelector('[data-tab="metrics"]');
                    if (metricsTab) metricsTab.classList.remove('hidden');
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
            // Build URL with filter parameters
            const params = new URLSearchParams({ limit: '50' });

            // Add rootActionType filter if only one type selected
            if (selectedRootActionTypes.size === 1) {
                params.append('rootActionType', Array.from(selectedRootActionTypes)[0]);
            }

            // Add rootOperation filter if set
            if (currentRootOperationFilter) {
                params.append('rootOperation', currentRootOperationFilter);
            }

            const response = await fetch(`/peekaboot/api/traces/insights?${params}`);
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

    const ALL_ROOT_ACTION_TYPES = ['HTTP_REQUEST', 'SCHEDULED_JOB', 'MESSAGE_CONSUMER', 'RPC_CALL', 'DATABASE', 'INTERNAL', 'UNKNOWN'];

    function renderTracesTab() {
        const listEl = document.getElementById('traces-list');
        const noTracesEl = document.getElementById('no-traces');
        listEl.innerHTML = '';

        // Update filter indicator
        updateFilterIndicator();

        const traces = tracesData?.traces;
        if (!traces || traces.length === 0) {
            const noTracesMsg = features.traceCaptureMode === 'ALL'
                ? 'No traces recorded'
                : 'No error traces recorded';
            noTracesEl.querySelector('p').textContent = noTracesMsg;
            noTracesEl.classList.remove('hidden');
            return;
        }

        const filteredTraces = traces.filter(t =>
            selectedRootActionTypes.has(t.rootActionType || 'UNKNOWN'));

        if (filteredTraces.length === 0) {
            noTracesEl.querySelector('p').textContent = 'No traces match the selected filters';
            noTracesEl.classList.remove('hidden');
            return;
        }

        noTracesEl.classList.add('hidden');

        filteredTraces.forEach(trace => {
            listEl.appendChild(renderTraceItem(trace));
        });
    }

    function updateFilterIndicator() {
        const filterBanner = document.getElementById('traces-active-filter');
        const filterText = filterBanner?.querySelector('.active-filter-text');
        const clearBtn = document.getElementById('traces-filter-clear');

        if (!filterBanner || !filterText) return;

        const isTypeFiltered = selectedRootActionTypes.size < ALL_ROOT_ACTION_TYPES.length;
        const isOperationFiltered = currentRootOperationFilter !== null;
        const isFiltered = isTypeFiltered || isOperationFiltered;

        if (isFiltered && selectedRootActionTypes.size > 0) {
            let filterDescription = '';

            // Show type filter
            if (isTypeFiltered) {
                const activeFilters = Array.from(selectedRootActionTypes)
                    .map(type => ROOT_ACTION_LABELS[type] || type)
                    .join(', ');
                filterDescription = `Type: ${activeFilters}`;
            }

            // Show operation filter (e.g., scheduler target)
            if (isOperationFiltered) {
                const operationLabel = currentRootOperationFilter.split('.').pop();
                filterDescription += filterDescription ? ` | Target: ${operationLabel}` : `Target: ${operationLabel}`;
            }

            filterText.textContent = `Filtering: ${filterDescription}`;
            filterBanner.classList.remove('hidden');
            if (clearBtn) clearBtn.classList.remove('hidden');
        } else {
            filterBanner.classList.add('hidden');
            if (clearBtn) clearBtn.classList.add('hidden');
        }
    }

    function resetTracesFilter() {
        ALL_ROOT_ACTION_TYPES.forEach(type => selectedRootActionTypes.add(type));
        currentRootOperationFilter = null;
        document.querySelectorAll('#traces-filter input').forEach(cb => {
            cb.checked = true;
        });
        // Refetch with cleared filters
        tracesLoaded = false;
        fetchTraces();
    }

    function hasSlowIssues(span) {
        if (!span) return false;
        if ((span.issues || []).some(issue => issue.type === 'SLOW' || issue.type === 'VERY_SLOW')) return true;
        return (span.children || []).some(hasSlowIssues);
    }

    function renderTraceItem(trace) {
        const item = document.createElement('div');
        item.className = 'trace-item';
        if (trace.traceId) {
            item.dataset.traceId = trace.traceId;
        }

        const traceIdShort = trace.traceId ? trace.traceId.substring(0, 16) + '...' : 'unknown';
        const startTime = trace.startTimeMs ? formatDate(new Date(trace.startTimeMs).toISOString()) : '-';
        const duration = PeekabootUtils.formatDurationMs(trace.durationMs);
        const hasErrors = trace.status === 'HAS_ERRORS';
        const hasSlow = hasSlowIssues(trace.rootSpan);

        const actionType = trace.rootActionType || 'UNKNOWN';
        const actionIcon = ROOT_ACTION_ICONS[actionType] || ROOT_ACTION_ICONS.UNKNOWN;
        const actionLabel = ROOT_ACTION_LABELS[actionType] || ROOT_ACTION_LABELS.UNKNOWN;

        // Root operation path/name (fallback to action label if empty)
        const rootOperation = trace.rootOperation || '';

        let statusBadge = '';
        if (hasErrors) {
            statusBadge = '<span class="trace-badge error">ERROR</span>';
        } else if (hasSlow) {
            statusBadge = '<span class="trace-badge warning">SLOW</span>';
        }

        // Link to scheduled tasks for SCHEDULED_JOB traces
        const schedulerLink = actionType === 'SCHEDULED_JOB'
            ? '<a href="#" class="trace-scheduler-link" title="View Scheduled Tasks">&#128337;</a>'
            : '';

        // Build stats for second line (only non-zero values)
        const statParts = [];

        // Query stats (warn class for slow queries >100ms)
        const queryCount = trace.summary?.queries?.count || 0;
        const queryDuration = trace.summary?.queries?.totalDurationMs || 0;
        if (queryCount > 0) {
            const queryDurationStr = PeekabootUtils.formatDurationMs(queryDuration);
            const qClass = queryDuration > 100 ? ' warn' : '';
            statParts.push(`<span class="stat-group${qClass}"><span class="stat-count">${queryCount}</span> ${queryCount === 1 ? 'query' : 'queries'} <span class="stat-duration">${queryDurationStr}</span></span>`);
        }

        // Log counts (errors and warnings)
        const errorCount = trace.summary?.logs?.errorCount || 0;
        const warnCount = trace.summary?.logs?.warnCount || 0;
        if (errorCount > 0) {
            statParts.push(`<span class="log-count error">${errorCount} ${errorCount === 1 ? 'error' : 'errors'}</span>`);
        }
        if (warnCount > 0) {
            statParts.push(`<span class="log-count warn">${warnCount} ${warnCount === 1 ? 'warning' : 'warnings'}</span>`);
        }

        const statsHtml = statParts.length > 0
            ? statParts.join('<span class="stat-separator">|</span>')
            : '';

        // Path display: use rootOperation or fallback to actionLabel
        const pathDisplay = rootOperation
            ? PeekabootUtils.escapeHtml(rootOperation)
            : `<span class="trace-action-label">${PeekabootUtils.escapeHtml(actionLabel)}</span>`;

        item.innerHTML = `
            <div class="trace-header">
                <div class="trace-main-line">
                    <span class="trace-action-type" title="${PeekabootUtils.escapeHtml(actionLabel)}">${actionIcon}</span>
                    <span class="trace-path" title="${PeekabootUtils.escapeHtml(rootOperation || actionLabel)}">${pathDisplay}</span>
                    <span class="trace-duration">${duration}</span>
                    ${statusBadge}
                    ${schedulerLink}
                </div>
                <div class="trace-stats">
                    <code class="trace-id">${PeekabootUtils.escapeHtml(traceIdShort)}</code>
                    <span class="trace-time">${startTime}</span>
                    ${statsHtml}
                </div>
            </div>
        `;

        const header = item.querySelector('.trace-header');
        header.addEventListener('click', (e) => {
            // Don't open trace detail if clicking the scheduler link
            if (e.target.closest('.trace-scheduler-link')) return;
            if (trace.traceId && window.PeekabootTraceDetail) {
                PeekabootTraceDetail.open(trace.traceId);
                setHash('traces', trace.traceId);
            }
        });

        // Add click handler for scheduler link
        const schedLink = item.querySelector('.trace-scheduler-link');
        if (schedLink) {
            schedLink.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                navigateToScheduledTasks();
            });
        }

        return item;
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

    function activateTab(tabName) {
        const tabButtons = document.querySelectorAll('#main-tabs .tab');
        const tabContents = document.querySelectorAll('.tab-content');

        // Validate tab exists
        const button = document.querySelector(`[data-tab="${tabName}"]`);
        if (!button) {
            tabName = 'dashboard'; // Fallback to dashboard
        }

        tabButtons.forEach(tab => tab.classList.remove('active'));
        tabContents.forEach(content => content.classList.remove('active'));

        const targetButton = document.querySelector(`[data-tab="${tabName}"]`);
        if (targetButton) targetButton.classList.add('active');

        const content = document.getElementById(`${tabName}-tab`);
        if (content) content.classList.add('active');

        // Load traces on demand
        if (tabName === 'traces' && !tracesLoaded) {
            fetchTraces();
        }

        // Load metrics on demand
        if (tabName === 'metrics' && !metricsLoaded) {
            fetchMetrics();
        }
    }

    function initTabs() {
        const tabButtons = document.querySelectorAll('#main-tabs .tab');

        tabButtons.forEach(button => {
            button.addEventListener('click', (e) => {
                e.preventDefault();
                const tabName = button.dataset.tab;
                activateTab(tabName);
                setHash(tabName); // Update URL
            });
        });

        // Listen for browser back/forward
        window.addEventListener('hashchange', handleHashChange);

        // Handle initial hash on page load
        const { tab } = parseHash();
        if (tab !== 'dashboard') {
            // Defer to allow DOM to initialize
            setTimeout(() => handleHashChange(), 0);
        }
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

    function initTracesFilter() {
        document.querySelectorAll('#traces-filter input').forEach(cb => {
            cb.addEventListener('change', () => {
                if (cb.checked) {
                    selectedRootActionTypes.add(cb.value);
                } else {
                    selectedRootActionTypes.delete(cb.value);
                }
                // refetch: tracesData may have been narrowed server-side by a
                // previous single-type or rootOperation filter
                fetchTraces();
            });
        });

        // Clear filter button
        const clearBtn = document.getElementById('traces-filter-clear');
        if (clearBtn) {
            clearBtn.addEventListener('click', resetTracesFilter);
        }
    }

    function initMetricsFilter() {
        const filterInput = document.getElementById('metrics-filter');
        if (filterInput) {
            filterInput.addEventListener('input', (e) => {
                renderMetricsTab(e.target.value.trim());
            });
        }
    }

    async function fetchMetrics() {
        const listEl = document.getElementById('metrics-list');
        const noMetricsEl = document.getElementById('no-metrics');
        const countEl = document.getElementById('metrics-count');

        if (!listEl) return;

        listEl.innerHTML = '<div class="loading"><div class="loading-spinner"></div><p>Loading metrics...</p></div>';
        if (noMetricsEl) noMetricsEl.classList.add('hidden');

        try {
            const response = await fetch('/peekaboot/api/metrics');
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            metricsData = await response.json();
            metricsLoaded = true;
            renderMetricsTab();
        } catch (error) {
            console.error('Error fetching metrics:', error);
            listEl.innerHTML = `<div class="error-banner"><span class="message">Failed to load metrics: ${error.message}</span></div>`;
        }
    }

    function renderMetricsTab(filterQuery = '') {
        const listEl = document.getElementById('metrics-list');
        const noMetricsEl = document.getElementById('no-metrics');
        const countEl = document.getElementById('metrics-count');

        if (!listEl) return;
        listEl.innerHTML = '';

        const metrics = metricsData?.metrics;
        if (!metrics || metrics.length === 0) {
            if (noMetricsEl) noMetricsEl.classList.remove('hidden');
            if (countEl) countEl.textContent = '';
            return;
        }

        if (noMetricsEl) noMetricsEl.classList.add('hidden');

        // Filter metrics by name or tag
        const filteredMetrics = filterQuery
            ? metrics.filter(m => matchesMetricFilter(m, filterQuery))
            : metrics;

        // Update count
        if (countEl) {
            countEl.textContent = filterQuery
                ? `${filteredMetrics.length} / ${metrics.length} metrics`
                : `${metrics.length} metrics`;
        }

        if (filteredMetrics.length === 0) {
            listEl.innerHTML = `<p class="no-data">No metrics matching "${PeekabootUtils.escapeHtml(filterQuery)}"</p>`;
            return;
        }

        // Render metric groups
        filteredMetrics.forEach(metric => {
            const groupEl = document.createElement('div');
            groupEl.className = 'metric-group';

            const headerEl = document.createElement('div');
            headerEl.className = 'metric-group-header collapsed';

            const typeBadgeClass = getMetricTypeBadgeClass(metric.type);
            const unitDisplay = metric.baseUnit ? `<span class="metric-unit">${PeekabootUtils.escapeHtml(metric.baseUnit)}</span>` : '';

            headerEl.innerHTML = `
                <span class="metric-name">${highlightText(metric.name, filterQuery)}</span>
                <span class="metric-type-badge ${typeBadgeClass}">${PeekabootUtils.escapeHtml(metric.type)}</span>
                ${unitDisplay}
                <span class="metric-measurement-count">${metric.measurements.length} measurement${metric.measurements.length !== 1 ? 's' : ''}</span>
            `;

            const contentEl = document.createElement('div');
            contentEl.className = 'metric-group-content collapsed';

            // Description
            if (metric.description) {
                const descEl = document.createElement('div');
                descEl.className = 'metric-description';
                descEl.textContent = metric.description;
                contentEl.appendChild(descEl);
            }

            // Measurements
            const measurementsEl = document.createElement('div');
            measurementsEl.className = 'metric-measurements';

            metric.measurements.forEach(measurement => {
                const measEl = document.createElement('div');
                measEl.className = 'metric-measurement';

                // Tags
                const tagsHtml = Object.entries(measurement.tags || {})
                    .map(([k, v]) => `<span class="metric-tag">${highlightText(k, filterQuery)}=${highlightText(v, filterQuery)}</span>`)
                    .join('');

                // Statistics
                const statsHtml = (measurement.statistics || [])
                    .map(stat => `<span class="metric-stat"><span class="metric-stat-name">${PeekabootUtils.escapeHtml(stat.name)}</span><span class="metric-stat-value">${formatMetricValue(stat.value, metric.baseUnit)}</span></span>`)
                    .join('');

                measEl.innerHTML = `
                    <div class="metric-tags">${tagsHtml || '<span class="metric-no-tags">no tags</span>'}</div>
                    <div class="metric-stats">${statsHtml}</div>
                `;

                measurementsEl.appendChild(measEl);
            });

            contentEl.appendChild(measurementsEl);

            headerEl.addEventListener('click', () => {
                contentEl.classList.toggle('collapsed');
                headerEl.classList.toggle('collapsed');
            });

            groupEl.appendChild(headerEl);
            groupEl.appendChild(contentEl);
            listEl.appendChild(groupEl);
        });
    }

    function matchesMetricFilter(metric, query) {
        const lowerQuery = query.toLowerCase();

        // Match metric name
        if (metric.name.toLowerCase().includes(lowerQuery)) return true;

        // Match tags
        for (const measurement of metric.measurements) {
            for (const [key, value] of Object.entries(measurement.tags || {})) {
                if (key.toLowerCase().includes(lowerQuery) || value.toLowerCase().includes(lowerQuery)) {
                    return true;
                }
            }
        }

        return false;
    }

    function getMetricTypeBadgeClass(type) {
        switch (type?.toUpperCase()) {
            case 'GAUGE': return 'type-gauge';
            case 'COUNTER': return 'type-counter';
            case 'TIMER': return 'type-timer';
            case 'DISTRIBUTION_SUMMARY': return 'type-distribution';
            case 'LONG_TASK_TIMER': return 'type-long-task';
            default: return 'type-other';
        }
    }

    function formatMetricValue(value, unit) {
        if (value === null || value === undefined || isNaN(value)) return '-';

        // Format bytes
        if (unit === 'bytes') {
            return formatBytes(value);
        }

        // Format seconds as duration
        if (unit === 'seconds') {
            if (value < 0.001) return `${(value * 1000000).toFixed(0)} \u00B5s`;
            if (value < 1) return `${(value * 1000).toFixed(2)} ms`;
            return `${value.toFixed(3)} s`;
        }

        // Format large numbers with grouping
        if (Math.abs(value) >= 1000) {
            return value.toLocaleString(currentLocale, { maximumFractionDigits: 2 });
        }

        // Format small decimals
        if (value !== 0 && Math.abs(value) < 0.01) {
            return value.toExponential(2);
        }

        return value.toLocaleString(currentLocale, { maximumFractionDigits: 4 });
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

            const url = new URL(API_ENDPOINT, window.location.origin);
            url.searchParams.set('locale', currentLocale);
            const response = await fetch(url);

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }

            peekabootData = await response.json();
            if (peekabootData.server) {
                serverTimezone = peekabootData.server;
            }
            updateTimezoneDisplay();
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
        // keep the user's typed filters across auto-refreshes
        renderEnvironmentTab(currentFilterValue('env-filter'));
        renderFlywayTab();
        renderLoggersTab(currentFilterValue('loggers-filter'));
        renderConfigTab(currentFilterValue('config-filter'));
        renderScheduledTasksTab();
    }

    function currentFilterValue(inputId) {
        return document.getElementById(inputId)?.value.trim() || '';
    }

    function renderDashboardTab() {
        const { application, runtime, dataSources, health } = peekabootData;

        renderBuildInfo(application?.build);
        renderGitInfo(application?.git);
        renderSpringInfo(application);
        renderJavaInfo(application);
        renderOsInfo(runtime);
        renderJvmDefaults(peekabootData.server);
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
                ? `<span class="ds-health-badge ${isUp ? 'is-up' : 'is-down'}">${PeekabootUtils.escapeHtml(ds.health)}</span>`
                : '';

            const dbProduct = ds.databaseProduct
                ? (ds.databaseProduct.displayName || ds.databaseProduct)
                : '-';

            card.innerHTML = `
                <div class="card-header">
                    <span class="icon">&#128450;</span>
                    <span>${PeekabootUtils.escapeHtml(ds.name || 'DataSource')}</span>
                    ${healthBadge}
                </div>
                <div class="card-body">
                    <div class="info-row"><span class="info-label">Database</span><span class="info-value">${PeekabootUtils.escapeHtml(ds.databaseName || '-')}</span></div>
                    <div class="info-row"><span class="info-label">Host</span><span class="info-value mono">${PeekabootUtils.escapeHtml(hostsStr)}</span></div>
                    <div class="info-row"><span class="info-label">User</span><span class="info-value">${PeekabootUtils.escapeHtml(ds.username || '-')}</span></div>
                    <div class="info-row"><span class="info-label">Product</span><span class="info-value">${PeekabootUtils.escapeHtml(String(dbProduct))}</span></div>
                    <div class="info-row"><span class="info-label">Driver</span><span class="info-value">${PeekabootUtils.escapeHtml(ds.driver || '-')}</span></div>
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
                <span class="ds-param-key">${PeekabootUtils.escapeHtml(key)}</span>
                <span class="ds-param-value ${isSensitive ? 'sensitive' : ''}">${PeekabootUtils.escapeHtml(displayValue || '-')}</span>
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

    function renderOsInfo(runtime) {
        const container = document.getElementById('os-info');
        container.innerHTML = '';

        const os = runtime?.os;
        const process = runtime?.process;

        if (!os && !process) {
            container.innerHTML = '<p class="no-data">No system info available</p>';
            return;
        }

        if (os) {
            if (os.name) container.appendChild(createInfoRow('OS', `${os.name} ${os.version || ''}`));
            if (os.arch) container.appendChild(createInfoRow('Architecture', os.arch));
        }

        if (process) {
            let userDisplay = process.username || '';
            if (process.uid != null) {
                userDisplay += ` (uid=${process.uid}, gid=${process.gid})`;
            }
            container.appendChild(createInfoRow('User', userDisplay));
            container.appendChild(createInfoRow('PID', process.pid, true));

            if (process.parentProcesses && process.parentProcesses.length > 0) {
                const tree = process.parentProcesses
                    .map(p => p.command ? `${p.command}(${p.pid})` : String(p.pid))
                    .reverse()
                    .join(' \u2192 ');
                container.appendChild(createInfoRow('Process Tree', tree, true));
            }
        }
    }

    function renderJvmDefaults(server) {
        const container = document.getElementById('jvm-defaults-info');
        container.innerHTML = '';

        if (!server) {
            container.innerHTML = '<p class="no-data">No JVM defaults available</p>';
            return;
        }

        if (server.locale) {
            const localeDisplay = server.localeDisplay ? ` (${server.localeDisplay})` : '';
            container.appendChild(createInfoRow('Locale', `${server.locale}${localeDisplay}`));
        }
        if (server.timezone) {
            const tzDisplay = server.timezoneOffset ? ` (UTC${server.timezoneOffset})` : '';
            container.appendChild(createInfoRow('Timezone', `${server.timezone}${tzDisplay}`));
        }
        if (server.currentTime) {
            const serverTime = new Date(server.currentTime);
            container.appendChild(createInfoRow('Server Time', formatDate(serverTime, { dateStyle: 'medium', timeStyle: 'medium' })));
        }
        if (server.fileEncoding) container.appendChild(createInfoRow('File Encoding', server.fileEncoding));
        if (server.availableProcessors) container.appendChild(createInfoRow('CPU Cores', server.availableProcessors));
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
                <span class="memory-label">${PeekabootUtils.escapeHtml(name)}</span>
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
                    detailsHtml = `<div class="component-details">${PeekabootUtils.escapeHtml(detailsText)}</div>`;
                }
            }

            card.innerHTML = `
                <div class="component-header">
                    <span class="component-name">${PeekabootUtils.escapeHtml(component.name)}</span>
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
        const expandedKeys = getExpandedGroupKeys(container, 'property-header');
        container.innerHTML = '';

        if (!env?.propertySources || env.propertySources.length === 0) {
            container.innerHTML = '<p class="no-data">No environment properties available</p>';
            return;
        }

        // Show active profiles at top
        if (env.activeProfiles && env.activeProfiles.length > 0) {
            const profilesEl = document.createElement('div');
            profilesEl.className = 'active-profiles';
            profilesEl.innerHTML = `<strong>Active Profiles:</strong> ${env.activeProfiles.map(p => `<span class="profile-badge">${PeekabootUtils.escapeHtml(p)}</span>`).join(' ')}`;
            container.appendChild(profilesEl);
        }

        // Pre-filter sources and their properties
        const filteredSources = env.propertySources
            .map(source => ({
                name: source.name || 'Unknown Source',
                properties: (source.properties || []).filter(prop =>
                    matchesFilter(prop.key, prop.value, filterQuery)
                )
            }))
            .filter(source => source.properties.length > 0);

        if (filteredSources.length === 0 && filterQuery) {
            container.innerHTML = `<p class="no-data">No properties matching "${PeekabootUtils.escapeHtml(filterQuery)}"</p>`;
            return;
        }

        renderCollapsibleGroups(container, filteredSources, {
            groupClass: 'property-source',
            headerClass: 'property-header',
            listClass: 'property-list',
            groupKey: (source) => source.name,
            expandedKeys,
            renderHeader: (source) => `
                <span class="property-name">${highlightText(source.name, filterQuery)}</span>
                <span class="property-count">${source.properties.length} properties</span>
            `,
            renderItems: (source, listEl) => {
                source.properties.forEach(prop => {
                    const item = document.createElement('div');
                    item.className = 'property-item';
                    item.innerHTML = `
                        <span class="property-key">${highlightText(prop.key, filterQuery)}</span>
                        <span class="property-value">${highlightText(formatValue(prop.value), filterQuery)}</span>
                    `;
                    listEl.appendChild(item);
                });
            }
        });
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
                    <span class="flyway-version">V${PeekabootUtils.escapeHtml(migration.version)}</span>
                    <span class="flyway-description">${PeekabootUtils.escapeHtml(migration.description)}</span>
                    <span class="flyway-status ${statusClass}">${PeekabootUtils.escapeHtml(migration.state)}</span>
                </div>
                <div class="flyway-details">
                    <span class="flyway-time ${isSlow ? 'slow' : ''}">&#9201; ${migration.executionTime}ms</span>
                    <span class="flyway-date">${formatDate(migration.installedOn)}</span>
                    <span class="flyway-type">${PeekabootUtils.escapeHtml(migration.type)}</span>
                </div>
                <div class="flyway-script">${PeekabootUtils.escapeHtml(migration.script)}</div>
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
        const expandedKeys = getExpandedGroupKeys(container, 'logger-group-header');
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

        // Pre-filter packages and their loggers
        const filteredPackages = packages
            .map(group => ({
                packageName: group.packageName,
                loggers: group.loggers.filter(logger => {
                    if (configuredOnly && !logger.configuredLevel) return false;
                    if (filterQuery && !logger.name.toLowerCase().includes(filterQuery.toLowerCase())) return false;
                    return true;
                })
            }))
            .filter(group => group.loggers.length > 0);

        if (filteredPackages.length === 0 && filterQuery) {
            container.innerHTML = `<p class="no-data">No loggers matching criteria</p>`;
            return;
        }

        renderCollapsibleGroups(container, filteredPackages, {
            groupClass: 'logger-group',
            headerClass: 'logger-group-header',
            listClass: 'logger-group-list',
            groupKey: (group) => group.packageName,
            expandedKeys,
            renderHeader: (group) => `
                <span class="logger-group-name">${highlightText(group.packageName, filterQuery)}</span>
                <span class="logger-group-count">${group.loggers.length} loggers</span>
            `,
            renderItems: (group, listEl) => {
                group.loggers.forEach(logger => {
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
            }
        });
    }

    // Config Tab
    function renderConfigTab(filterQuery = '') {
        const configInfo = peekabootData?.config;
        const container = document.getElementById('config-groups');
        const configTab = document.querySelector('[data-tab="config"]');

        if (!container) return;
        const expandedKeys = getExpandedGroupKeys(container, 'config-group-header');
        container.innerHTML = '';

        const groups = configInfo?.groups;
        if (!groups || groups.length === 0) {
            container.innerHTML = '<p class="no-data">No configuration properties available</p>';
            return;
        }

        if (configTab) configTab.classList.remove('hidden');

        // Pre-filter groups and their properties
        const filteredGroups = groups
            .map(group => ({
                prefix: group.prefix,
                properties: group.properties.filter(prop => {
                    if (!filterQuery) return true;
                    const matchesKey = prop.key.toLowerCase().includes(filterQuery.toLowerCase());
                    const matchesValue = prop.value && prop.value.toLowerCase().includes(filterQuery.toLowerCase());
                    return matchesKey || matchesValue;
                })
            }))
            .filter(group => group.properties.length > 0);

        if (filteredGroups.length === 0) {
            container.innerHTML = filterQuery
                ? `<p class="no-data">No properties matching "${PeekabootUtils.escapeHtml(filterQuery)}"</p>`
                : '<p class="no-data">No configuration properties available</p>';
            return;
        }

        renderCollapsibleGroups(container, filteredGroups, {
            groupClass: 'config-group',
            headerClass: 'config-group-header',
            listClass: 'config-list',
            groupKey: (group) => group.prefix,
            expandedKeys,
            renderHeader: (group) => `
                <span class="config-group-name">${highlightText(group.prefix, filterQuery)}</span>
                <span class="config-group-count">${group.properties.length} properties</span>
            `,
            renderItems: (group, listEl) => {
                group.properties.forEach(prop => {
                    const item = document.createElement('div');
                    item.className = 'config-item';

                    const isSensitive = /password|secret|key|token|credential/i.test(prop.key);

                    item.innerHTML = `
                        <span class="config-key">${highlightText(prop.key, filterQuery)}</span>
                        <span class="config-value ${isSensitive ? 'sensitive' : ''}">${highlightText(prop.value || '-', filterQuery)}</span>
                    `;

                    listEl.appendChild(item);
                });
            }
        });
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
                    ? (task.scheduleDescription || task.schedule)
                    : formatFixedInterval(task.intervalMs);

                const viewTracesLink = features.tracing
                    ? `<a href="#" class="task-traces-link" data-target="${PeekabootUtils.escapeHtml(task.target)}" title="View traces for this scheduler">&#128269;</a>`
                    : '';

                item.innerHTML = `
                    <div class="task-row">
                        <div class="task-left">
                            <span class="task-type-badge ${type.toLowerCase()}">${typeLabel}</span>
                            <span class="task-schedule-value" title="${PeekabootUtils.escapeHtml(task.schedule)}">${PeekabootUtils.escapeHtml(scheduleDisplay)}</span>
                        </div>
                        <div class="task-right">
                            <span class="task-timing-item"><span class="task-timing-label">Last:</span> ${task.lastExecution ? formatDate(task.lastExecution) : 'Never'}</span>
                            <span class="task-timing-item"><span class="task-timing-label">Next:</span> ${task.nextExecution ? formatDate(task.nextExecution) : '-'}</span>
                            <span class="task-status ${statusClass}">${PeekabootUtils.escapeHtml(task.lastStatus || 'PENDING')}</span>
                        </div>
                    </div>
                    <div class="task-target-row">
                        <span class="task-target" title="${PeekabootUtils.escapeHtml(task.target)}">${PeekabootUtils.escapeHtml(targetShort)}</span>
                        ${viewTracesLink}
                    </div>
                    ${task.lastException ? `<div class="task-exception"><span class="task-exception-label">Error during last Execution:</span> ${PeekabootUtils.escapeHtml(task.lastException)}</div>` : ''}
                `;

                // Add click handler for view traces link
                const tracesLink = item.querySelector('.task-traces-link');
                if (tracesLink) {
                    tracesLink.addEventListener('click', (e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        const target = tracesLink.dataset.target;
                        navigateToTracesWithFilter('SCHEDULED_JOB', target);
                    });
                }

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
            case 'PENDING': case 'RUNNING': return 'status-pending';
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

    function matchesFilter(key, value, filter) {
        if (!filter) return true;
        const filterLower = filter.toLowerCase();
        return key.toLowerCase().includes(filterLower) ||
               String(value).toLowerCase().includes(filterLower);
    }

    function highlightText(text, query) {
        if (!query) return PeekabootUtils.escapeHtml(text);

        const textStr = String(text);
        const lowerText = textStr.toLowerCase();
        const lowerQuery = query.toLowerCase();

        let result = '';
        let lastIndex = 0;
        let index = lowerText.indexOf(lowerQuery);

        while (index !== -1) {
            result += PeekabootUtils.escapeHtml(textStr.substring(lastIndex, index));
            result += `<mark>${PeekabootUtils.escapeHtml(textStr.substring(index, index + query.length))}</mark>`;
            lastIndex = index + query.length;
            index = lowerText.indexOf(lowerQuery, lastIndex);
        }

        result += PeekabootUtils.escapeHtml(textStr.substring(lastIndex));
        return result;
    }

    function createInfoRow(label, value, isMonospace = false) {
        const row = document.createElement('div');
        row.className = 'info-row';
        row.innerHTML = `
            <span class="info-label">${PeekabootUtils.escapeHtml(label)}</span>
            <span class="info-value${isMonospace ? ' mono' : ''}">${PeekabootUtils.escapeHtml(String(value))}</span>
        `;
        return row;
    }

    /**
     * Renders collapsible groups with consistent expand/collapse behavior.
     * @param {HTMLElement} container - The container to append groups to
     * @param {Array} groups - Array of group data objects
     * @param {Object} options - Configuration options
     * @param {string} options.groupClass - CSS class for the group wrapper
     * @param {string} options.headerClass - CSS class for the header element
     * @param {string} options.listClass - CSS class for the list element
     * @param {Function} options.renderHeader - Function(group) returning header innerHTML
     * @param {Function} options.renderItems - Function(group, listEl) that populates list element
     */
    function renderCollapsibleGroups(container, groups, options) {
        const {
            groupClass,
            headerClass,
            listClass,
            renderHeader,
            renderItems,
            groupKey,
            expandedKeys
        } = options;

        groups.forEach(group => {
            const groupEl = document.createElement('div');
            groupEl.className = groupClass;

            const key = groupKey ? groupKey(group) : '';
            groupEl.dataset.groupKey = key;
            const expanded = key && expandedKeys && expandedKeys.has(key);

            const headerEl = document.createElement('div');
            headerEl.className = expanded ? headerClass : `${headerClass} collapsed`;
            headerEl.innerHTML = renderHeader(group);

            const listEl = document.createElement('div');
            listEl.className = expanded ? listClass : `${listClass} collapsed`;
            renderItems(group, listEl);

            headerEl.addEventListener('click', () => {
                listEl.classList.toggle('collapsed');
                headerEl.classList.toggle('collapsed');
            });

            groupEl.appendChild(headerEl);
            groupEl.appendChild(listEl);
            container.appendChild(groupEl);
        });
    }

    /**
     * Captures which collapsible groups are currently expanded, so a
     * re-render (e.g. the 30s auto-refresh) can restore their state.
     * Must be called before the container is cleared.
     */
    function getExpandedGroupKeys(container, headerClass) {
        if (!container) return new Set();
        return new Set(Array.from(container.querySelectorAll(`.${headerClass}:not(.collapsed)`))
                .map(header => header.parentElement?.dataset.groupKey)
                .filter(Boolean));
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

    function initTimezoneControls() {
        const saved = localStorage.getItem('peekaboot-use-server-tz');
        useServerTimezone = saved === 'true';

        const toggle = document.getElementById('timezone-toggle');
        if (toggle) {
            toggle.addEventListener('click', () => {
                useServerTimezone = !useServerTimezone;
                localStorage.setItem('peekaboot-use-server-tz', String(useServerTimezone));
                updateTimezoneDisplay();
                renderData();
            });
        }
        updateTimezoneDisplay();
    }

    function initLocaleSelector() {
        const saved = localStorage.getItem('peekaboot-locale');
        if (saved) {
            currentLocale = saved;
        }

        const select = document.getElementById('locale-select');
        if (select) {
            const option = select.querySelector(`option[value="${currentLocale}"]`);
            if (option) {
                select.value = currentLocale;
            }

            select.addEventListener('change', () => {
                currentLocale = select.value;
                localStorage.setItem('peekaboot-locale', currentLocale);
                fetchData();
            });
        }
    }

    function updateTimezoneDisplay() {
        const label = document.getElementById('timezone-label');
        const info = document.getElementById('tz-info');

        if (label) {
            label.textContent = useServerTimezone ? 'Server' : 'Browser';
        }

        if (info) {
            const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
            const serverTz = serverTimezone ? serverTimezone.timezone : 'Unknown';
            info.textContent = useServerTimezone ? serverTz : browserTz;
        }
    }

    function formatDate(dateStr) {
        if (!dateStr) return '-';
        try {
            const date = new Date(dateStr);
            const options = {
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit'
            };

            if (useServerTimezone && serverTimezone) {
                options.timeZone = serverTimezone.timezone;
            }

            return date.toLocaleDateString(currentLocale, options);
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
