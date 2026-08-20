/**
 * Peekaboot dashboard bootstrap.
 *
 * Owns theme wiring, the tab registry, hash routing, auto-refresh, the locale/timezone
 * controls and the single error banner. Each tab's own rendering lives in its own module
 * under tabs/ (see the contract documented on overview.js) - this file never renders
 * domain data itself, only decides which tab module to hand the fetched payload to.
 */
import {createClient} from '../shared/api.js';
import {tabStrip} from '../shared/components.js';
import {resolveTheme, applyTheme, storeTheme, watchTheme} from '../shared/theme.js';
import {formatDateTime} from '../shared/format.js';
import {open as openTraceDetail, close as closeTraceDetail} from '../trace-detail/trace-detail.js';
import * as overview from './tabs/overview.js';
import * as environment from './tabs/environment.js';
import * as flyway from './tabs/flyway.js';
import * as loggers from './tabs/loggers.js';
import * as config from './tabs/config.js';
import * as scheduledTasks from './tabs/scheduled-tasks.js';
import * as metrics from './tabs/metrics.js';
import * as traces from './tabs/traces.js';

const API_PATH = '/api/actuator/all/insights';
const REFRESH_INTERVAL_MS = 30000;
const TABS = [overview, environment, flyway, loggers, config, scheduledTasks, metrics, traces];
const TAB_IDS = TABS.map(tab => tab.id);

const client = createClient();

// Mirrors shared/theme.js's storedTheme()/storeTheme() pattern: storage can be blocked
// (private browsing, some embedded/iframe contexts, strict cookie policies), and a throw
// here happens during module evaluation, so an unguarded read would blank the whole
// dashboard before any code runs.
function safeStorageGet(key) {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
}

function safeStorageSet(key, value) {
    try {
        localStorage.setItem(key, value);
    } catch {
        /* preference simply will not persist */
    }
}

let data = null;
let features = {};
let mainTabs = null;
let refreshTimer = null;
let isPaused = false;
let locale = safeStorageGet('peekaboot-locale') || navigator.language || 'en-US';
let useServerTimezone = safeStorageGet('peekaboot-use-server-tz') === 'true';
let serverTimezone = null;

// --- Hash routing -----------------------------------------------------------------

function parseHash() {
    const hash = window.location.hash.slice(1);
    if (!hash) return {tab: 'dashboard', detail: null};
    const parts = hash.split('/');
    return {tab: parts[0] || 'dashboard', detail: parts[1] || null};
}

function setHash(tab, detail = null) {
    const hash = detail ? `#${tab}/${detail}` : `#${tab}`;
    if (window.location.hash !== hash) {
        history.pushState(null, '', hash);
    }
}

function handleHashChange() {
    const {tab, detail} = parseHash();
    const tabId = resolveTabId(tab);
    mainTabs.select(tabId, {silent: true});
    showTab(tabId);
    renderTabById(tabId);
    if (tabId === 'traces' && detail) {
        expandTraceById(detail);
    } else {
        // Browser Back removed the detail segment, or landed on a different tab -
        // close.js is idempotent when nothing is open, so no guard is needed here.
        closeTraceDetail();
    }
}

function expandTraceById(traceId) {
    // Validate traceId to prevent selector injection
    if (!traceId || !/^[a-zA-Z0-9_-]+$/.test(traceId)) {
        console.warn('Invalid trace ID:', traceId);
        return;
    }
    openTraceDetail(traceId, {
        // Closing the overlay (ESC, buttons) must also clean the hash, otherwise a
        // reload would unexpectedly reopen the trace.
        onClose: () => {
            const {tab, detail} = parseHash();
            if (tab === 'traces' && detail === traceId) setHash('traces');
        }
    });
}

/**
 * Passed to every tab module as context.navigate. `payload`, when given, is routed to
 * the target tab's own (optional) applyFilter(payload) - kept a distinct third argument
 * rather than overloading `detail`, which handleHashChange treats as a trace id for the
 * traces tab specifically. A payload skips the generic renderTabById() call below: the
 * target tab's applyFilter is expected to trigger its own re-render/fetch, and calling
 * both would fire two overlapping fetches for one navigation.
 *
 * renderTabById() is also skipped when the target tab was already the active one - a
 * call with no real tab switch (e.g. traces.js's openTrace() using this purely to update
 * the hash for a trace it is opening/closing, while staying on the traces tab) has
 * nothing new to render, and would otherwise force an unwanted extra fetch on tabs like
 * metrics/traces that fetch their own data.
 */
function navigate(tabId, detail = null, payload = null) {
    const resolvedId = resolveTabId(tabId);
    const wasAlreadyActive = document.getElementById(`${resolvedId}-tab`)?.classList.contains('active') ?? false;
    mainTabs.select(resolvedId, {silent: true});
    showTab(resolvedId);
    setHash(resolvedId, detail);
    if (payload) {
        TABS.find(tab => tab.id === resolvedId)?.applyFilter?.(payload);
    } else if (!wasAlreadyActive) {
        renderTabById(resolvedId);
    }
}

// --- Tab strip ----------------------------------------------------------------------

/**
 * tabId comes straight from the URL hash (see handleHashChange) or from another tab
 * module's navigate() call - never trusted outright, so an unknown id (e.g. a stale
 * or hand-edited hash) falls back to the dashboard tab instead of leaving every panel
 * hidden or the tab strip's selection pointing at nothing.
 */
function resolveTabId(tabId) {
    return TAB_IDS.includes(tabId) ? tabId : 'dashboard';
}

/** Toggles which `.pk-tab-panel` is visible - independent of the tab strip's own
 * selection state (aria-selected/tabIndex), which tabStrip's select() owns. */
function showTab(tabId) {
    document.querySelectorAll('.pk-tab-panel').forEach(section => section.classList.remove('active'));
    const content = document.getElementById(`${tabId}-tab`);
    if (content) content.classList.add('active');
}

function initTabs() {
    const initialTabId = resolveTabId(parseHash().tab);
    mainTabs = tabStrip(document.getElementById('main-tabs'), TABS.map(tab => ({id: tab.id, label: tab.label})), {
        onSelect: tabId => {
            showTab(tabId);
            setHash(tabId);
            renderTabById(tabId);
        },
        initial: initialTabId
    });
    // tabStrip's own initial select() is silent (button-only), so on a deep-linked
    // boot (e.g. "#environment") the panel would otherwise stay on Dashboard until
    // the deferred handleHashChange() below runs - a one-macrotask window where the
    // accessibility tree (aria-selected) and the visible panel disagree. Setting the
    // panel synchronously here keeps them atomic; handleHashChange() still runs to
    // do the actual data render (and re-showTab()s the same id - a harmless no-op).
    showTab(initialTabId);

    window.addEventListener('hashchange', handleHashChange);

    // Handle initial hash on page load
    const {tab} = parseHash();
    if (tab !== 'dashboard') {
        // Defer to allow the DOM (and the initial fetchData() call) to settle first
        setTimeout(() => handleHashChange(), 0);
    }
}

// --- Data fetching --------------------------------------------------------------------

function currentContext() {
    return {
        client,
        locale,
        timeZone: useServerTimezone && serverTimezone ? serverTimezone.timezone : undefined,
        navigate,
        features
    };
}

/**
 * Fetched once at boot, before the first fetchData() - feeds every registered tab's
 * (optional) isAvailable(data, features) check, which drives the traces/metrics tab
 * buttons (see traces.js/metrics.js's own isAvailable) once the first fetchData() ->
 * renderData() cycle runs.
 */
async function fetchFeatures() {
    try {
        features = await client.get('/api/features') || {};
    } catch (error) {
        console.warn('Could not fetch features:', error);
    }
}

/**
 * Renders one tab against the latest data, and shows/hides its strip button according
 * to its (optional) isAvailable(data, features) check - the replacement for
 * peekaboot.js's scattered "unhide this tab once its data shows up" calls, now driven
 * by the tab contract instead of one-off code per tab.
 */
function renderTab(tab) {
    if (!data) return;
    const available = tab.isAvailable ? tab.isAvailable(data, features) : true;
    const button = document.querySelector(`.pk-tab[data-tab="${tab.id}"]`);
    if (button) button.classList.toggle('hidden', !available);
    if (!available) return;

    const section = document.getElementById(`${tab.id}-tab`);
    if (section) tab.render(section, data, currentContext());
}

/** Renders every registered tab against the latest data - the 30s auto-refresh path. */
function renderData() {
    TABS.forEach(renderTab);
}

/**
 * Renders a single tab by id - used wherever a tab becomes newly active (tab-strip
 * click, hash-driven navigation) so that metrics.js/traces.js, which fetch their own
 * data and skip that fetch while their container isn't visible (see their own doc
 * comments), get a render the moment they're switched to instead of waiting for the
 * next 30s auto-refresh cycle.
 */
function renderTabById(tabId) {
    const tab = TABS.find(t => t.id === tabId);
    if (tab) renderTab(tab);
}

async function fetchData() {
    const loadingEl = document.getElementById('loading');
    const errorEl = document.getElementById('error');
    const refreshIcon = document.getElementById('refresh-icon');

    refreshIcon.classList.add('pk-spinning');

    try {
        if (!data) loadingEl.classList.remove('hidden');
        errorEl.classList.add('hidden');

        const result = await client.get(API_PATH, {params: {locale}});
        if (result === null) return; // superseded by a newer request

        data = result;
        if (data.server) serverTimezone = data.server;
        updateTimezoneDisplay();
        renderData();
        updateLastUpdated();

        loadingEl.classList.add('hidden');
    } catch (error) {
        loadingEl.classList.add('hidden');
        errorEl.classList.remove('hidden');
        errorEl.querySelector('.message').textContent = `Failed to load data: ${error.message}`;
    } finally {
        refreshIcon.classList.remove('pk-spinning');
    }
}

function updateLastUpdated() {
    const {locale: currentLocale, timeZone} = currentContext();
    const time = formatDateTime(new Date(), {locale: currentLocale, timeZone, hour: '2-digit', minute: '2-digit', second: '2-digit'});
    document.getElementById('last-updated').textContent = `Updated ${time}`;
}

// --- Auto-refresh ---------------------------------------------------------------------

function initRefreshControls() {
    document.getElementById('refresh-btn').addEventListener('click', () => fetchData());
    document.getElementById('pause-btn').addEventListener('click', togglePause);
    startAutoRefresh();
}

function startAutoRefresh() {
    if (refreshTimer) clearInterval(refreshTimer);
    refreshTimer = setInterval(() => {
        if (!isPaused) fetchData();
    }, REFRESH_INTERVAL_MS);
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

// --- Locale / timezone controls --------------------------------------------------------

function initLocaleSelector() {
    const select = document.getElementById('locale-select');
    if (select.querySelector(`option[value="${locale}"]`)) {
        select.value = locale;
    }
    select.addEventListener('change', () => {
        locale = select.value;
        safeStorageSet('peekaboot-locale', locale);
        fetchData();
    });
}

function initTimezoneControls() {
    document.getElementById('timezone-toggle').addEventListener('click', () => {
        useServerTimezone = !useServerTimezone;
        safeStorageSet('peekaboot-use-server-tz', String(useServerTimezone));
        updateTimezoneDisplay();
        renderData();
    });
    updateTimezoneDisplay();
}

function updateTimezoneDisplay() {
    document.getElementById('timezone-label').textContent = useServerTimezone ? 'Server' : 'Browser';
    const browserTz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const serverTz = serverTimezone ? serverTimezone.timezone : 'Unknown';
    document.getElementById('tz-info').textContent = useServerTimezone ? serverTz : browserTz;
}

// --- Theme ------------------------------------------------------------------------------

function updateThemeIcon(theme) {
    document.getElementById('theme-icon').textContent = theme === 'light' ? '☾' : '☀';
}

function initTheme() {
    const theme = resolveTheme();
    applyTheme(document.documentElement, theme);
    updateThemeIcon(theme);

    document.getElementById('theme-toggle').addEventListener('click', () => {
        const next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
        applyTheme(document.documentElement, next);
        storeTheme(next);
        updateThemeIcon(next);
    });

    watchTheme(next => {
        applyTheme(document.documentElement, next);
        updateThemeIcon(next);
    });
}

// --- Error banner -------------------------------------------------------------------------

function initErrorClose() {
    document.getElementById('error-close').addEventListener('click', () => {
        document.getElementById('error').classList.add('hidden');
    });
}

// --- Boot -------------------------------------------------------------------------------

async function init() {
    initTheme();
    initTabs();
    initRefreshControls();
    initTimezoneControls();
    initLocaleSelector();
    initErrorClose();
    await fetchFeatures();
    fetchData();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
