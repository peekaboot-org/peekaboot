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
import {readSetting, writeSetting} from '../shared/storage.js';
import {formatDateTime} from '../shared/format.js';
import {parseAppHash, pushAppHash, replaceAppHash} from '../shared/url-state.js';
import {openTraceDetail, closeTraceDetail} from '../trace-detail/trace-detail.js';
import * as overview from './tabs/overview.js';
import * as insights from './tabs/insights.js';
import * as lifecycle from './tabs/lifecycle.js';
import * as traces from './tabs/traces.js';
import * as meters from './tabs/meters.js';
import * as environment from './tabs/environment.js';
import * as flyway from './tabs/flyway.js';
import * as loggers from './tabs/loggers.js';
import * as config from './tabs/config.js';
import * as scheduledTasks from './tabs/scheduled-tasks.js';

const API_PATH = '/api/actuator/all/insights';
const REFRESH_INTERVAL_MS = 30000;
const TABS = [overview, insights, lifecycle, traces, meters, environment, flyway, loggers, config, scheduledTasks];
const TAB_IDS = TABS.map(tab => tab.id);

const client = createClient();

let data = null;
let features = {};
let mainTabs = null;
let refreshTimer = null;
let isPaused = false;
let locale = readSetting('peekaboot-locale') || navigator.language || 'en-US';
let useServerTimezone = readSetting('peekaboot-use-server-tz') === 'true';
let serverTimezone = null;
// Whether the next fetch should ask the API for real values instead of "******" -
// the Environment/Config tabs' unmask control (shared/unmask-control.js). Deliberately
// NOT persisted (no localStorage, unlike locale/timezone/theme above): a "show me
// secrets" toggle that survives a reload is a footgun, so a reload always starts masked.
let unmaskRequested = false;

// --- Hash routing -----------------------------------------------------------------

// True only while a render is happening as the direct result of handleHashChange() -
// a genuine hash change (address-bar edit, Back/Forward), or the deferred boot-time
// call for a deep link - as opposed to a programmatic tab switch or navigate() call.
// pushAppHash/replaceAppHash never fire 'hashchange' (see url-state.js's own doc
// comment), so every other render path (tab-strip clicks, cross-tab navigate()) leaves
// this false. Read by currentContext() below and exposed on the context as
// `urlIsAuthoritative`, so a tab's reconcile logic can tell "the user asked for exactly
// this URL, including a bare one" apart from "the URL just doesn't happen to carry this
// tab's params right now" (e.g. the tab strip's own bare hash push on every switch,
// where the tab's current filter state should survive instead of being cleared - see
// each tab's reconcileWithUrl/reconcileFilterWithUrl doc comment).
let urlChangeInProgress = false;

function handleHashChange() {
    const {tab, detail, subview, params} = parseAppHash();
    const tabId = resolveTabId(tab);
    mainTabs.select(tabId, {silent: true});
    showTab(tabId);
    urlChangeInProgress = true;
    try {
        renderTabById(tabId);
    } finally {
        urlChangeInProgress = false;
    }
    if (tabId === 'traces' && detail) {
        expandTraceById(detail, subview, params);
    } else {
        // Browser Back removed the detail segment, or landed on a different tab -
        // close.js is idempotent when nothing is open, so no guard is needed here.
        closeTraceDetail();
    }
}

/**
 * Builds the {initial, update} urlState object openTraceDetail expects - shared by
 * expandTraceById below (restoring subview/params from a hash-driven open) and
 * traces.js's own click-to-open path (via context.traceUrlState, always a fresh open with
 * nothing to restore) so the two entry points can't drift into two different update()
 * implementations.
 */
function buildTraceUrlState(traceId, subview = null, params = {}) {
    return {
        initial: {subview, params},
        update: (subview, params) => replaceAppHash({tab: 'traces', detail: traceId, subview, params})
    };
}

function expandTraceById(traceId, subview = null, params = {}) {
    // Validate traceId to prevent selector injection
    if (!traceId || !/^[a-zA-Z0-9_-]+$/.test(traceId)) {
        console.warn('Invalid trace ID:', traceId);
        return;
    }

    // handleHashChange fires on every Back/Forward step - including ones that only
    // replaced subview/params (see url-state.js's push/replace rule) - and the overlay can
    // also already be open for this trace via traces.js's own click-to-open path, which
    // calls openTraceDetail directly and never runs this function at all. Querying the
    // DOM (the overlay host's data-trace-id, set by trace-detail.js) instead of tracking
    // an "is it open" flag in this module stays correct regardless of which path opened
    // it; re-running openTraceDetail would otherwise tear down and rebuild the whole
    // overlay for a trace that's already open, flickering it.
    if (document.getElementById('peekaboot-trace-overlay')?.dataset.traceId === traceId) return;

    const {locale: currentLocale, timeZone, features: currentFeatures} = currentContext();
    openTraceDetail(traceId, {
        urlState: buildTraceUrlState(traceId, subview, params),
        locale: currentLocale,
        timeZone,
        features: currentFeatures,
        // Closing the overlay (ESC, buttons) must also clean the hash, otherwise a
        // reload would unexpectedly reopen the trace.
        onClose: () => {
            const {tab, detail} = parseAppHash();
            if (tab === 'traces' && detail === traceId) pushAppHash({tab: 'traces'});
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
    pushAppHash({tab: resolvedId, detail});
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
 * or hand-edited hash) falls back to the overview tab instead of leaving every panel
 * hidden or the tab strip's selection pointing at nothing.
 */
function resolveTabId(tabId) {
    return TAB_IDS.includes(tabId) ? tabId : 'overview';
}

/** Toggles which `.pk-tab-panel` is visible - independent of the tab strip's own
 * selection state (aria-selected/tabIndex), which tabStrip's select() owns. */
function showTab(tabId) {
    document.querySelectorAll('.pk-tab-panel').forEach(section => section.classList.remove('active'));
    const content = document.getElementById(`${tabId}-tab`);
    if (content) content.classList.add('active');
}

function initTabs() {
    const initialTabId = resolveTabId(parseAppHash().tab);
    mainTabs = tabStrip(document.getElementById('main-tabs'), TABS.map(tab => ({id: tab.id, label: tab.label})), {
        onSelect: tabId => {
            showTab(tabId);
            pushAppHash({tab: tabId});
            renderTabById(tabId);
        },
        initial: initialTabId
    });
    // tabStrip's own initial select() is silent (button-only), so on a deep-linked
    // boot (e.g. "#environment") the panel would otherwise stay on Overview until
    // init() runs the deep link's handleHashChange() - a window where the accessibility
    // tree (aria-selected) and the visible panel disagree. Setting the panel
    // synchronously here keeps them atomic; handleHashChange() still runs to do the
    // actual data render (and re-showTab()s the same id - a harmless no-op).
    showTab(initialTabId);

    window.addEventListener('hashchange', handleHashChange);
}

// --- Data fetching --------------------------------------------------------------------

function currentContext() {
    const {tab, detail, subview, params: urlParams} = parseAppHash();
    return {
        client,
        locale,
        timeZone: useServerTimezone && serverTimezone ? serverTimezone.timezone : undefined,
        navigate,
        features,
        unmaskRequested,
        toggleUnmask,
        // The active tab's own query params, and how it writes them back - see
        // url-state.js's push/replace rule: a filter change replaces, it never pushes.
        urlParams,
        // True only for a render triggered by handleHashChange() - a genuine hash
        // change or the boot-time deep-link kick - as opposed to a programmatic tab
        // switch/navigate() call. See urlChangeInProgress's own doc comment above.
        urlIsAuthoritative: urlChangeInProgress,
        // Deliberately re-parses the hash instead of closing over this call's own
        // tab/detail/subview above: a tab module can hold this context object (and so
        // this closure) far longer than the hash stays put underneath it - e.g. traces.js
        // opening/closing the trace overlay via context.navigate(), which skips a fresh
        // render (and so a fresh currentContext()) whenever the traces tab was already
        // active (see navigate()'s wasAlreadyActive guard). A stale capture here would let
        // a filter change made after such a close replace the hash with the closed
        // trace's own now-stale detail/subview, silently reopening it. Re-parsing at call
        // time makes this correct regardless of how long the closure has been sitting
        // around - detail/subview always come from whatever the hash actually says right
        // now, not whatever it said when this context object was built.
        setUrlParams: params => {
            const {tab: currentTab, detail: currentDetail, subview: currentSubview} = parseAppHash();
            // The params slot belongs to the deepest view - while a detail segment is open
            // (the trace overlay, on top of this tab's own now-background panel - see
            // traces.js's own reconcileWithUrl guard for the seed-direction half of this
            // same rule), the overlay owns it via its own urlState.update (buildTraceUrlState
            // above). A background tab's filter write here would otherwise replace the
            // overlay's own params (e.g. the Logs tab's level/q) wholesale on every
            // auto-refresh - see the regression test this fixes.
            if (currentDetail) return;
            replaceAppHash({tab: resolveTabId(currentTab), detail: currentDetail, subview: currentSubview, params});
        },
        // traces.js's own click-to-open path passes this straight into openTraceDetail's
        // urlState option, so a trace opened by clicking it gets the exact same live
        // tab/filter -> URL sync as one opened via a deep link - see buildTraceUrlState.
        traceUrlState: traceId => buildTraceUrlState(traceId)
    };
}

/**
 * Flips the shared unmask request and re-fetches - the Environment and Config tabs
 * both render off the one payload fetchData() pulls, so there is one shared "reveal"
 * state rather than a per-tab copy (see shared/unmask-control.js's doc comment for the
 * full reasoning). Mirrors the locale-select pattern below: a per-view setting change
 * simply triggers a fresh fetchData() call rather than optimistically patching the DOM.
 */
function toggleUnmask() {
    unmaskRequested = !unmaskRequested;
    fetchData();
}

/**
 * Fetched once at boot, before the first fetchData() - feeds every registered tab's
 * (optional) isAvailable(data, features) check, which drives the traces/meters tab
 * buttons (see traces.js/meters.js's own isAvailable) once the first fetchData() ->
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
 * to its (optional) isAvailable(data, features) check - driven by the tab contract
 * instead of one-off code per tab.
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
 * click, hash-driven navigation) so that meters.js/traces.js, which fetch their own
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

        const result = await client.get(API_PATH, {params: {locale, unmask: unmaskRequested ? 'true' : undefined}});
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

    const label = isPaused ? 'Resume auto-refresh' : 'Pause auto-refresh';
    pauseIcon.innerHTML = isPaused ? '&#9654;' : '&#10074;&#10074;';
    pauseBtn.title = label;
    pauseBtn.setAttribute('aria-label', label);
}

// --- Locale / timezone controls --------------------------------------------------------

/**
 * The select lists a few common locales; the browser's own (navigator.language, the
 * default the dates are formatted with) and a stored choice outside that list get an
 * option added, so the control always shows the locale actually in effect rather than
 * its first entry.
 */
function initLocaleSelector() {
    const select = document.getElementById('locale-select');
    [navigator.language, locale].filter(Boolean).forEach(tag => {
        if (!select.querySelector(`option[value="${CSS.escape(tag)}"]`)) {
            select.add(new Option(tag.toUpperCase(), tag));
        }
    });
    select.value = locale;
    select.addEventListener('change', () => {
        locale = select.value;
        writeSetting('peekaboot-locale', locale);
        fetchData();
    });
}

function initTimezoneControls() {
    document.getElementById('timezone-toggle').addEventListener('click', () => {
        useServerTimezone = !useServerTimezone;
        writeSetting('peekaboot-use-server-tz', String(useServerTimezone));
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
    // "Server"/"Browser" alone is not a usable button name - say which way it switches.
    document.getElementById('timezone-toggle').setAttribute('aria-label', useServerTimezone
            ? 'Timezone: server. Switch to browser timezone'
            : 'Timezone: browser. Switch to server timezone');
}

// --- Theme ------------------------------------------------------------------------------

function updateThemeIcon(theme) {
    document.getElementById('theme-icon').textContent = theme === 'light' ? '☾' : '☀';
    // The glyph is aria-hidden, so the button's name has to say what pressing it does.
    document.getElementById('theme-toggle').setAttribute(
            'aria-label', theme === 'light' ? 'Switch to dark theme' : 'Switch to light theme');
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
    // A deep link is honoured only now: the overlay it may open keeps the features it is
    // handed for its whole lifetime, so it must not open on the empty placeholder.
    if (parseAppHash().tab !== 'overview') handleHashChange();
    fetchData();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
