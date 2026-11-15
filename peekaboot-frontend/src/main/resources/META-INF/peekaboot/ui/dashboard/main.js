/**
 * Peekaboot dashboard bootstrap.
 *
 * Owns theme wiring, the tab registry and lifecycle, hash routing, auto-refresh, the
 * locale/timezone controls and the single error banner. Each tab's own rendering lives in
 * its own module under tabs/ (the contract is documented in peekaboot-frontend/README.md,
 * "How to add a dashboard tab") - this file never renders domain data itself, only shows,
 * updates and hides the one tab that is on screen.
 */
import {createClient} from '../shared/api.js';
import {tabStrip} from '../shared/components.js';
import {el} from '../shared/dom.js';
import {bindTheme, applyTheme, storeTheme} from '../shared/theme.js';
import {readSetting, writeSetting, readLocaleSetting, LOCALE_STORAGE_KEY} from '../shared/storage.js';
import {formatDateTimeWith} from '../shared/format.js';
import {parseAppHash, buildAppHash, pushAppHash, replaceAppHash} from '../shared/url-state.js';
import {openTraceDetail, closeTraceDetail, shownTraceTarget} from '../trace-detail/trace-detail.js';
import * as overview from './tabs/overview.js';
import * as insights from './tabs/insights.js';
import * as lifecycle from './tabs/lifecycle.js';
import * as traces from './tabs/traces.js';
import * as meters from './tabs/meters.js';
import * as environment from './tabs/environment.js';
import * as flyway from './tabs/flyway.js';
import * as liquibase from './tabs/liquibase.js';
import * as loggers from './tabs/loggers.js';
import * as config from './tabs/config.js';
import * as scheduledTasks from './tabs/scheduled-tasks.js';

const API_PATH = '/api/actuator/all/insights';
const REFRESH_INTERVAL_MS = 30000;
const TABS = [overview, insights, lifecycle, traces, meters, environment, flyway, liquibase, loggers, config, scheduledTasks];
const TAB_IDS = TABS.map(tab => tab.id);
// the note runHook() leaves in a tab's own panel, never one a tab rendered further down
const TAB_FAILURE = ':scope > .pk-tab-failure';

const client = createClient();

let data = null;
// null until /api/features has answered; every tab's isAvailable() check reads them, so
// fetchData() loads no payload before that
let features = null;
let mainTabs = null;
// tab id -> the instance its module's create() returned, made the first time the tab is used
const instances = new Map();
// The tab whose panel is on screen, and the one whose show() has run: they differ until
// the first payload arrives, since no tab is shown without data.
let visibleTabId = null;
let shownTabId = null;
let refreshTimer = null;
let isPaused = false;
let locale = readLocaleSetting() || navigator.language || 'en-US';
let useServerTimezone = readSetting('peekaboot-use-server-tz') === 'true';
let serverTimezone = null;
// Whether the next fetch should ask the API for real values instead of "******" -
// the Environment/Config tabs' unmask control (shared/unmask-control.js). Deliberately
// NOT persisted (no localStorage, unlike locale/timezone/theme above): a "show me
// secrets" toggle that survives a reload is a footgun, so a reload always starts masked.
let unmaskRequested = false;

// --- Hash routing -----------------------------------------------------------------

// True only while a render is the direct result of handleHashChange() (a real hash
// change, or the boot-time deep link); pushAppHash/replaceAppHash never fire
// 'hashchange'. Exposed as `urlIsAuthoritative` so a tab can tell "the user asked for
// exactly this URL" from "the URL just doesn't carry my params" (see url-filter.js).
let urlChangeInProgress = false;

function handleHashChange() {
    // fetchData() replays the hash once the features are in
    if (!features) return;
    const {tab, detail, subview, params} = parseAppHash();
    const tabId = resolveTabId(tab);
    // an unknown id renders Overview, so the URL says Overview too - the same correction
    // fallBackToOverview() makes for a tab this instance does not have
    if (tabId !== tab) replaceAppHash({tab: tabId});
    mainTabs.select(tabId, {silent: true});
    showTab(tabId);
    urlChangeInProgress = true;
    try {
        enterTab(tabId);
    } finally {
        urlChangeInProgress = false;
    }
    if (tabId === 'traces' && detail) {
        expandTraceById(detail, subview, params);
    } else {
        // Browser Back removed the detail segment, or landed on a different tab -
        // closeTraceDetail() is a no-op when nothing is open.
        closeTraceDetail();
    }
}

/**
 * The full identity of what the overlay is showing - not just the trace id. One trace id
 * can open two different targets: the whole trace, or one async row's own subtree at
 * "#traces/<id>/spans?root=<spanId>". Reuses buildAppHash so this is exactly the hash such
 * a target would occupy.
 */
function overlayTargetKey(traceId, subview, params) {
    return buildAppHash({tab: 'traces', detail: traceId, subview, params});
}

/**
 * The {initial, update} urlState object openTraceDetail expects: what the overlay
 * restores at open time, and how its own tab switches and filter changes are written
 * back to the hash.
 */
function buildTraceUrlState(traceId, subview = null, params = {}) {
    return {
        initial: {subview, params},
        update: (subview, params) => replaceAppHash({tab: 'traces', detail: traceId, subview, params})
    };
}

function expandTraceById(traceId, subview = null, params = {}) {
    // Only something shaped like a trace id reaches the API path; a hand-edited hash must not.
    if (!traceId || !/^[a-zA-Z0-9_-]+$/.test(traceId)) {
        console.warn('Invalid trace ID:', traceId);
        return;
    }

    // Already open for exactly this target - id, subview and params alike (a Back/Forward
    // step that only changed one of those, or a click-to-open that already ran) - re-opening
    // would tear the overlay down and rebuild it for nothing. Comparing the id alone would
    // treat an async row's own-subtree URL as "the same" as its trace's whole-trace URL and
    // silently no-op the second click. The overlay's own record is the one source of truth.
    const shown = shownTraceTarget();
    if (shown && overlayTargetKey(shown.traceId, shown.subview, shown.params)
            === overlayTargetKey(traceId, subview, params)) return;

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
 * Passed to the traces tab as context.openTrace: its click-to-open path, on the same
 * overlay wiring a deep link uses. subtreeRootSpanId is present only for an async row,
 * which opens scoped to its own subtree - the Spans tab, rooted there - rather than the
 * whole trace.
 */
function openTrace(traceId, subtreeRootSpanId) {
    const subview = subtreeRootSpanId ? 'spans' : null;
    const params = subtreeRootSpanId ? {root: subtreeRootSpanId} : {};
    pushAppHash({tab: 'traces', detail: traceId, subview, params});
    expandTraceById(traceId, subview, params);
}

// --- Tab strip ----------------------------------------------------------------------

/**
 * tabId comes straight from the URL hash (see handleHashChange) - never trusted
 * outright, so an unknown id (e.g. a stale or hand-edited hash) falls back to the
 * overview tab instead of leaving every panel hidden or the tab strip's selection
 * pointing at nothing.
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
    visibleTabId = tabId;
}

function initTabs() {
    const initialTabId = resolveTabId(parseAppHash().tab);
    // the strip's buttons are static in index.html, so only the ids are needed here
    mainTabs = tabStrip(document.getElementById('main-tabs'), TABS.map(tab => ({id: tab.id})), {
        onSelect: tabId => {
            showTab(tabId);
            pushAppHash({tab: tabId});
            enterTab(tabId);
        },
        initial: initialTabId
    });
    // tabStrip's own initial select() is silent (button-only), so on a deep-linked
    // boot (e.g. "#environment") the panel would otherwise stay on Overview until
    // fetchData() replays the hash - a window where the accessibility tree
    // (aria-selected) and the visible panel disagree. Setting the panel synchronously
    // here keeps them atomic; handleHashChange() still runs to enter the tab (and
    // re-showTab()s the same id - a harmless no-op).
    showTab(initialTabId);

    window.addEventListener('hashchange', handleHashChange);
}

// --- Data fetching --------------------------------------------------------------------

/** What a tab's show() and update() receive beside the payload; only the visible tab is handed one. */
function currentContext() {
    const {params: urlParams} = parseAppHash();
    return {
        client,
        locale,
        timeZone: useServerTimezone && serverTimezone ? serverTimezone.timezone : undefined,
        openTrace,
        features: features ?? {},
        unmaskRequested,
        toggleUnmask,
        // The active tab's own query params, and how it writes them back - see
        // url-state.js's push/replace rule: a filter change replaces, it never pushes.
        urlParams,
        // See urlChangeInProgress above.
        urlIsAuthoritative: urlChangeInProgress,
        // Re-parses the hash at call time: a tab holds its context far longer than the
        // hash stays put underneath it, and a stale capture could write the closed
        // overlay's detail/subview back and silently reopen it.
        setUrlParams: params => {
            const {tab: currentTab, detail: currentDetail, subview: currentSubview} = parseAppHash();
            // The params slot belongs to the deepest view: while the overlay is open it
            // owns it via its own urlState.update (buildTraceUrlState above), and the
            // tab's write would replace the overlay's params wholesale.
            if (currentDetail) return;
            replaceAppHash({tab: resolveTabId(currentTab), detail: currentDetail, subview: currentSubview, params});
        }
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

function tabById(tabId) {
    return TABS.find(tab => tab.id === tabId);
}

function isAvailable(tab) {
    return tab.isAvailable ? tab.isAvailable(data, features) : true;
}

/**
 * Calls one lifecycle hook of a tab - show, update or hide - and keeps a failure inside
 * that tab: a hook that throws or rejects leaves a note at the top of the tab's own panel,
 * which the next hook that succeeds takes away again. The page banner stays for a payload
 * that failed to load.
 */
async function runHook(tab, name, ...args) {
    const section = document.getElementById(`${tab.id}-tab`);
    try {
        if (!instances.has(tab.id)) instances.set(tab.id, tab.create(section));
        await instances.get(tab.id)[name]?.(...args);
        section.querySelector(TAB_FAILURE)?.remove();
    } catch (error) {
        console.error(`The ${tab.id} tab failed in ${name}():`, error);
        let note = section.querySelector(TAB_FAILURE);
        if (!note) {
            note = el('div', {className: 'pk-tab-failure', attrs: {role: 'alert'}});
            section.prepend(note);
        }
        note.textContent = `This tab could not be shown: ${error.message}`;
    }
}

/**
 * Makes a tab the one on screen - wherever a tab becomes newly active (tab-strip click,
 * hash-driven navigation, the first payload) - and hides the one shown before it. Nothing
 * is shown before the first payload has arrived; renderData() enters the visible tab then.
 */
function enterTab(tabId) {
    if (!data) return;
    const tab = tabById(tabId);
    if (!isAvailable(tab)) {
        fallBackToOverview();
        return;
    }
    if (shownTabId && shownTabId !== tabId) runHook(tabById(shownTabId), 'hide');
    shownTabId = tabId;
    runHook(tab, 'show', data, currentContext());
}

/**
 * A deep link or a stale bookmark can name a tab this instance does not have (tracing
 * switched off, no Flyway). An empty panel with no tab selected looks broken, so the
 * reader lands on Overview instead and the hash is corrected to it - a replace, like
 * every other correction of a URL that asked for a state that does not exist.
 */
function fallBackToOverview() {
    mainTabs.select('overview', {silent: true});
    showTab('overview');
    replaceAppHash({tab: 'overview'});
    // A "#traces/<id>" link with tracing off may have opened the overlay before the data
    // arrived; the hash no longer names it, so its own close-time cleanup stays a no-op.
    closeTraceDetail();
    enterTab('overview');
}

/**
 * Brings the page in line with the latest data - a new payload, or a timezone switch:
 * every strip button follows its tab's (optional) isAvailable(data, features) check, and
 * the visible tab alone is updated. A hidden tab is rendered when it is switched to.
 */
function renderData() {
    if (!data) return;
    TABS.forEach(tab => {
        const button = document.querySelector(`.pk-tab[data-tab="${tab.id}"]`);
        if (button) button.classList.toggle('hidden', !isAvailable(tab));
    });

    const visibleTab = tabById(visibleTabId);
    // not shown yet on the first payload, and no longer available once its feature is gone
    if (shownTabId === visibleTabId && isAvailable(visibleTab)) runHook(visibleTab, 'update', data, currentContext());
    else enterTab(visibleTabId);
}

async function fetchData() {
    const loadingEl = document.getElementById('loading');
    const errorEl = document.getElementById('error');
    const refreshIcon = document.getElementById('refresh-icon');

    refreshIcon.classList.add('pk-spinning');

    try {
        if (!data) loadingEl.classList.remove('hidden');
        errorEl.classList.add('hidden');

        if (!features) {
            // inside the try: a failure fails this refresh, and the next one asks again
            const loaded = await client.get('/api/features');
            if (loaded === null) return; // superseded by a newer request
            features = loaded;
            // The hash is honoured only now: the overlay it may open keeps the features it
            // is handed for its whole lifetime, and handleHashChange() ignored it until here.
            handleHashChange();
        }

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
    const time = formatDateTimeWith(new Date(), {hour: '2-digit', minute: '2-digit', second: '2-digit'}, {locale: currentLocale, timeZone});
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
    pauseIcon.textContent = isPaused ? '▶' : '❚❚';
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
        writeSetting(LOCALE_STORAGE_KEY, locale);
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
    bindTheme(document.documentElement, updateThemeIcon);

    document.getElementById('theme-toggle').addEventListener('click', () => {
        const next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
        applyTheme(document.documentElement, next);
        storeTheme(next);
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

function init() {
    initTheme();
    initTabs();
    initRefreshControls();
    initTimezoneControls();
    initLocaleSelector();
    initErrorClose();
    fetchData();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    init();
}
