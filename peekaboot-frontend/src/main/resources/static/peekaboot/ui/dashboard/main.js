/**
 * Peekaboot dashboard bootstrap.
 *
 * Owns theme wiring, the tab registry, hash routing, auto-refresh, the locale/timezone
 * controls and the single error banner. Each tab's own rendering lives in its own module
 * under tabs/ (see the contract documented on overview.js) - this file never renders
 * domain data itself, only decides which tab module to hand the fetched payload to.
 */
import {createClient} from '../shared/api.js';
import {resolveTheme, applyTheme, storeTheme, watchTheme} from '../shared/theme.js';
import {formatDateTime} from '../shared/format.js';
import {open as openTraceDetail, close as closeTraceDetail} from '../trace-detail/trace-detail.js';
import * as overview from './tabs/overview.js';

const API_PATH = '/api/actuator/all/insights';
const REFRESH_INTERVAL_MS = 30000;
const TABS = [overview /* remaining tabs added in Tasks 14 and 15 */];

const client = createClient();

let data = null;
let features = {};
let refreshTimer = null;
let isPaused = false;
let locale = localStorage.getItem('peekaboot-locale') || navigator.language || 'en-US';
let useServerTimezone = localStorage.getItem('peekaboot-use-server-tz') === 'true';
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
    activateTab(tab);
    if (tab === 'traces' && detail) {
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

/** Passed to every tab module as context.navigate. */
function navigate(tabId, detail = null) {
    activateTab(tabId);
    setHash(tabId, detail);
}

// --- Tab strip ----------------------------------------------------------------------

/**
 * tabName comes straight from the URL hash (see handleHashChange) - found by comparing
 * dataset.tab in a loop, not by interpolating it into a querySelector attribute
 * selector, so a hash like "#a\"]" can't break out of the selector string.
 */
function activateTab(tabName) {
    const buttons = document.querySelectorAll('#main-tabs .pk-tab');
    const sections = document.querySelectorAll('.pk-tab-panel');

    let targetButton = Array.from(buttons).find(button => button.dataset.tab === tabName);
    if (!targetButton) {
        tabName = 'dashboard'; // Fallback to dashboard
        targetButton = Array.from(buttons).find(button => button.dataset.tab === tabName);
    }

    buttons.forEach(button => button.setAttribute('aria-selected', 'false'));
    sections.forEach(section => section.classList.remove('active'));

    if (targetButton) targetButton.setAttribute('aria-selected', 'true');

    const content = document.getElementById(`${tabName}-tab`);
    if (content) content.classList.add('active');
}

function initTabs() {
    document.querySelectorAll('#main-tabs .pk-tab').forEach(button => {
        button.addEventListener('click', (e) => {
            e.preventDefault();
            const tabName = button.dataset.tab;
            activateTab(tabName);
            setHash(tabName);
        });
    });

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
        navigate
    };
}

/**
 * Fetched once at boot, before the first fetchData() - feeds every registered tab's
 * (optional) isAvailable(data, features) check, and also unhides the traces/metrics
 * tab buttons directly (those two tabs aren't in TABS yet - Task 15 - so there is no
 * tab module for the isAvailable path to drive for them until then).
 */
async function fetchFeatures() {
    try {
        features = await client.get('/api/features') || {};
    } catch (error) {
        console.warn('Could not fetch features:', error);
        return;
    }
    if (features.tracing) document.querySelector('.pk-tab[data-tab="traces"]')?.classList.remove('hidden');
    if (features.metrics) document.querySelector('.pk-tab[data-tab="metrics"]')?.classList.remove('hidden');
}

/**
 * Renders every registered tab against the latest data, and shows/hides each tab's
 * strip button according to its (optional) isAvailable(data, features) check - the
 * replacement for peekaboot.js's scattered "unhide this tab once its data shows up"
 * calls, now driven by the tab contract instead of one-off code per tab.
 */
function renderData() {
    if (!data) return;
    const context = currentContext();

    TABS.forEach(tab => {
        const available = tab.isAvailable ? tab.isAvailable(data, features) : true;
        const button = document.querySelector(`.pk-tab[data-tab="${tab.id}"]`);
        if (button) button.classList.toggle('hidden', !available);
        if (!available) return;

        const section = document.getElementById(`${tab.id}-tab`);
        if (section) tab.render(section, data, context);
    });
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
        localStorage.setItem('peekaboot-locale', locale);
        fetchData();
    });
}

function initTimezoneControls() {
    document.getElementById('timezone-toggle').addEventListener('click', () => {
        useServerTimezone = !useServerTimezone;
        localStorage.setItem('peekaboot-use-server-tz', String(useServerTimezone));
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
