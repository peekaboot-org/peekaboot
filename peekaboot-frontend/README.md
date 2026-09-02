# Peekaboot Frontend

Static resources served from `/peekaboot/ui/`, backing three separate UI surfaces that
share one design system:

- **Dashboard** (`dashboard/`) — the standalone app-insights page.
- **Dev toolbar** (`toolbar/`) — rendered into host-application pages by `DevToolbarFilter`
  and enhanced in place by `toolbar.js`,
  rendered inside a shadow root so host-page CSS can't reach it and vice versa.
- **Trace-detail overlay** (`trace-detail/`) — a full-screen dialog, also shadow-rooted,
  opened from either the dashboard or the toolbar.

No build step. Plain ES modules and CSS, served as-is.

```
static/peekaboot/ui/
├── assets/          tokens.css, base.css, components.css — the shared design system
│                    favicon-16/32.png, logo-mark.png, logo-mark-dark.png — the icon set
├── shared/          api.js, components.js, copyable.js, filtered-group-tab.js, format.js,
│                    http-status.js, markup.js, root-actions.js, severity.js,
│                    shadow-styles.js, span-names.js, storage.js, theme.js, trace-stats.js,
│                    unmask-control.js, url-filter.js, url-state.js
├── dashboard/       index.html, dashboard.css, main.js, tabs/*.js  (10 tabs, plus the
│                    Insights tab's own insights-chart.js, insights-markers.js and
│                    insights-colors.js)
├── trace-detail/    trace-detail.css, trace-detail.js, tabs/*.js   (4 tabs)
├── toolbar/         toolbar.css, toolbar.js
└── vendor/          uplot/ — the only third-party code, loaded on demand (see below)
```

The Insights tab charts with [uPlot](https://github.com/leeoniya/uPlot), vendored under
`vendor/uplot/` (MIT, version pinned in `VERSION`). It is the one exception to "plain ES
modules": `insights-chart.js` injects the script and stylesheet the first time a chart
actually has to be drawn, so no other page — and no dashboard that never opens the tab —
loads it.

## The three shared layers

`assets/` holds three stylesheets, each with a distinct job, loaded in this order by
every surface:

1. **`tokens.css`** — custom properties only, nothing else. This is the documented
   *override point*: a consuming application re-themes all of Peekaboot by overriding
   these `--pk-*` properties and nothing else. It defines the light palette on `:root`
   and the dark palette under `[data-theme="dark"]`.
2. **`base.css`** — the reset (`box-sizing`) and bare element defaults (`body`, `mark`).
   No component classes here.
3. **`components.css`** — the `.pk-*` primitives (badge, group, kv row, meter, button,
   tab strip, empty state, spinner) that every surface's own CSS builds on. A surface's
   own stylesheet (`dashboard.css`, `toolbar.css`, `trace-detail.css`) never re-declares
   one of these; it only adds surface-specific chrome. A variant one surface needs
   becomes a modifier here (`.pk-table--kv`, the overlay's key/value table), so the
   primitive stays the single definition.

### The doubled-selector mechanism

This is the trick that makes the split work across three very different DOM contexts.
`tokens.css` and `base.css` each declare their rules twice:

```css
:root, :host { --pk-bg: #ffffff; /* ... */ }
[data-theme="dark"], :host([data-theme="dark"]) { --pk-bg: #0d1117; /* ... */ }
```

`:root` matches the dashboard's own document; `:host` matches the shadow root of an
element these same `<link>` tags are loaded into. The identical file therefore works
unmodified whether it's linked into `dashboard/index.html`'s `<head>` or into the
toolbar's or overlay's shadow root — no surface-specific variant, no build step to
generate one.

## The icon set

`assets/` also holds four PNGs, all derived from one piece of source artwork — the
simplified Peekaboot mark (green hexagon, slate magnifier, green bars). The detailed
version of the logo is deliberately *not* used here: below roughly 96px its interior
detail (list rows, line chart, gloss) turns to noise, and the two places the UI shows a
logo are 26px and 18px.

| File | Used by |
|---|---|
| `favicon-16.png`, `favicon-32.png` | `<link rel="icon">` in `dashboard/index.html` |
| `logo-mark.png` (96px) | `.pk-header__logo` and the toolbar's dashboard link, light theme |
| `logo-mark-dark.png` (96px) | the same two, dark theme |

There are two variants because the mark is two-tone. Its slate magnifier (`#263238`)
measures 13.2:1 on a white background but **1.4:1 on the dark theme's `--pk-bg`** — it
simply disappears. `logo-mark-dark.png` is the same artwork with that slate recoloured to
a light neutral. Both surfaces swap it with a CSS `background-image` override rather than
swapping an `<img src>`, so no JavaScript is involved:

```css
[data-theme="dark"] .pk-header__logo   { background-image: url('../assets/logo-mark-dark.png'); }  /* dashboard */
:host([data-theme="dark"]) .pk-toolbar__link { background-image: url('../assets/logo-mark-dark.png'); }  /* shadow root */
```

The toolbar's `url()` resolves against `toolbar.css`'s own URL, which is why it works from
inside a shadow root without knowing `basePath`.

To regenerate from new source artwork (the masters live outside this repo, alongside it in
`peekaboot-org/assets/`), crop to the artwork's bounding box, centre it in a square with
~6% padding, and resample with Lanczos — the mark's anti-aliasing lives in the alpha
channel with full-strength RGB underneath, so a fuzzy colour replacement recolours it
cleanly without fringing:

```sh
magick peekaboot-logo-favicon.png -crop 909x1015+173+105 +repage \
       -background none -gravity center -extent 1076x1076 master.png
magick master.png -background none -filter Lanczos -resize 32x32 -strip favicon-32.png
magick master.png -fuzz 20% -fill '#e6edf3' -opaque '#263238' master-dark.png   # dark variant
```

## `shared/` module inventory

| Module | Exports |
|---|---|
| `api.js` | `createClient({basePath})` — fetch wrapper; a per-path generation counter makes an overtaken response resolve to `null` instead of racing a newer one. `BASE_PATH` — the default `basePath`, read off this module's own URL (`<context-path>/peekaboot`), so the dashboard and the overlay it opens follow a `server.servlet.context-path` without being told; the toolbar gets the same value from the server in its data blob. |
| `components.js` | `badge`, `kvRow`, `group`, `meter`, `groupList`, `expandedKeys`, `tabStrip` — the JS builders behind the `.pk-*` primitives. |
| `copyable.js` | `copyableIdHtml`, `copyableId`, `bindCopyables` — the click-to-copy trace/span id control, as an HTML string or a detached element, with one delegated click listener per root (document or shadow root). |
| `filtered-group-tab.js` | `filteredGroupTab({inputId, listId, select, filterGroup, key, header, items, extraTop, emptyMessage, noMatchMessage, urlFilter, decorate, afterRender, fetchData, loadingMessage, fetchErrorMessage})` — the shell of a dashboard tab that shows a filterable list of collapsible groups (module state, the filter input wired once, URL reconciliation, expansion restore, empty states); `config.js`, `environment.js`, `loggers.js` and `meters.js` are built on it and supply only what differs. `fetchData(context)` is the hook for a tab whose data comes from its own endpoint instead of the shared payload (`meters.js`) — called only while the tab is active, with loading/error states handled by the shell. |
| `format.js` | `formatDurationMs`, `formatLongDuration`, `formatInterval`, `formatBytes`, `formatHosts`, `formatDateTime`, `formatTimeOfDay`, `formatCount`, `formatMetricValue`, `formatTileValue`. |
| `http-status.js` | `statusLabel` (`404` → `"404 Not Found"`), `statusVariant` (the badge tier per response family). |
| `markup.js` | `escapeHtml`, `highlightText`, `MASK_LITERAL` — fallback for the backend's masked-value literal (`Features.maskLiteral`, `"******"`), used only by the surfaces that never load `/api/features` (the dev toolbar and the overlay it opens). |
| `unmask-control.js` | `renderUnmaskControl(slot, context)` — the Environment/Config "Show secrets" toggle. Renders nothing into an empty slot unless `context.features.unmaskingEnabled` is true; the frontend does not decide what is sensitive, only whether the reveal control can work at all. |
| `root-actions.js` | `ROOT_ACTION_TYPES`, `rootActionIcon`, `rootActionLabel` — the icon/label map for a trace's root action type (HTTP request, scheduled job, …). |
| `severity.js` | `durationSeverity(ms, features)`, `querySeverity(ms, features)`, `threshold(features, key)`, `DEFAULT_THRESHOLDS`, `issueSeverity(issues)`, `ISSUE_TYPES`, `LOG_LEVELS`, `logLevelVariant(level)`, `healthSeverity(status)` — the one place a duration, a span's issues, a log level or a health status is turned into a colour. See *Thresholds and the SLOW badge* below. |
| `shadow-styles.js` | `attachSharedStyles(shadowRoot, hostElement, basePath, ownSheetHref)` — links the shared sheets (plus the surface's own) into a shadow root; see below. |
| `span-names.js` | `buildSpanNames(rootSpan)` — spanId → name lookup, used by the overlay's Logs tab to name the span each log row belongs to. |
| `storage.js` | `readSetting`, `writeSetting` — guarded `localStorage` access for per-browser settings; a blocked store reads as `null` and writes are dropped instead of throwing during module evaluation. |
| `theme.js` | `THEME_STORAGE_KEY`, `resolveTheme`, `applyTheme`, `storeTheme`, `watchTheme`. |
| `trace-stats.js` | `traceStatParts(trace, features)` — a trace's stat line (query count with total query time, error and warning log counts) as detached elements; the Traces tab's rows and the dev toolbar's bar both render it, so neither can drift in wording or colouring. |
| `url-state.js` | `parseAppHash`, `buildAppHash`, `pushAppHash`, `replaceAppHash` — the `#<tab>[/<detail>[/<subview>]][?<query>]` hash routing format; structural segments (tab, detail) push a history entry, subview/params replace it. |
| `url-filter.js` | `reconcileFilterWithUrl(context, urlKeys, {seed, hasNonDefaultState, writeBack})` — the shared URL-authoritative-vs-current-state direction logic behind every dashboard tab's filter-URL reconciliation; `reconcileTextFilter`/`writeTextFilter(input, context)` — the single-text-input case built on it (config.js/environment.js/meters.js's own filter; loggers.js composes the lower-level helper directly for its q+checkbox pair, insights.js for its level and lifecycle.js for its page). |

## URL state (deep links)

Every dashboard view is addressable: `#<tab>[/<detail>[/<subview>]][?<params>]`
(`shared/url-state.js`). Structural segments (tab, detail) push a history entry;
subview and params are written with `replaceState`, so a filter keystroke or an
overlay tab switch never adds a Back stop of its own. Opening a URL restores the
state below, and changing that state rewrites the URL in place —
`shared/url-filter.js` holds the shared "URL vs. current state" direction rule every
tab applies (a tab-strip click's own bare hash push must not clear a filter; a
hand-edited bare hash must).

| View | URL params | Restored / written state |
|---|---|---|
| `#overview` | — | no view state of its own |
| `#insights` | `level` | the global aggregation level (a configured level index; the first level is the default and stays out of the URL — per-panel overrides are not URL state) |
| `#lifecycle` | `page` | the pager's 1-based page; page one stays out of the URL, an out-of-range value clamps to the last page and the URL is corrected |
| `#traces` | `bucket`, `type`, `op` | bucket (`all`/`errors`/`slow`), comma-separated root action types, root operation |
| `#traces/<traceId>` | — | opens the trace overlay on its Spans tab |
| `#traces/<traceId>/<subview>` | the overlay tab's own filters | `request`/`spans`/`queries`/`logs`; the Logs tab carries `q`, `level`, `span` |
| `#meters` | `q` | the text filter |
| `#environment` | `q` | the text filter |
| `#flyway` | — | no filter |
| `#loggers` | `q`, `configured` | the text filter and the configured-only checkbox (`configured=1`) |
| `#config` | `q` | the text filter |
| `#scheduled-tasks` | — | no filter (group expansion is deliberately not URL state, on any tab) |

Theme, locale and timezone are per-browser settings (`localStorage`), never URL
state — a shared link must not impose the sender's display preferences on the
reader. An invalid param value (an unknown bucket, level, log level or page) falls
back to its default instead of reaching the backend or filtering invisibly.

### Cross-links in the trace overlay

The overlay's tabs link into each other: a log row links to the span that wrote it,
a query span's gantt row links to its entry in the Queries tab, and each Queries
entry links back to its span row (`trace-detail.js`'s `goToSpan`/`goToQuery`; the
Logs tab's span-name button is the older third link, filtering the log list to a
span). A jump switches the overlay tab the way the strip would (`replaceState`,
params reset), scrolls to the target, moves keyboard focus onto it and marks it with
the temporary `.pk-jump-flash` highlight. The anchors are `data-span-id` on the
gantt rows and on `.pk-query-item` (the backend's `QueryInfo.spanId`).

## Thresholds and the SLOW badge

Every number the frontend colours a duration by comes from the backend, once, over
`GET /peekaboot/api/features` (the `Features` record): `slowSpanThresholdMs` and
`verySlowSpanThresholdMs` (`peekaboot.ui.tracing.*`, what `IssueDetector` raises SLOW and
VERY_SLOW at), `slowQueryThresholdMs` (SLOW_QUERY) and `slowTraceThresholdMs`
(`peekaboot.tracing.slow-trace-threshold-ms`, the Slow bucket's admission threshold; `null`
while tracing is off). The dashboard hands its `features` to every tab and to the overlay it
opens (`open(traceId, {locale, timeZone, features})`); `severity.js`'s `DEFAULT_THRESHOLDS`
mirrors the backend defaults, keyed by the same names, for the one path that has no
features in hand — the dev toolbar, which never fetches them, and the overlay it opens.
`SharedModuleIT` pins the defaults to the backend properties' defaults.

Every comparison is **at-or-above (`>=`)**, on both sides of the wire: `IssueDetector`
raises SLOW/VERY_SLOW/SLOW_QUERY at `duration >= threshold`, and `durationSeverity()`/
`querySeverity()` colour with the same comparison — a 100 ms span is already SLOW at the
default thresholds, a 50 ms query already slow.

Where a span's own issues are in hand, `issueSeverity(span.issues)` is the backend's verdict
and is what colours the gantt duration cells; `durationSeverity()` re-derives a severity only
for durations no issue describes — a trace's total, a trace's total query time, a Flyway
migration's execution time. The Queries tab's per-query SLOW label uses `querySeverity()`,
the query threshold behind the backend's SLOW_QUERY issue (`slowQueryThresholdMs`, 50 ms by
default) — not the span thresholds.

The Traces tab's **SLOW badge means "some span in this trace carries a SLOW or VERY_SLOW
issue"** — the backend's per-trace `slow` flag, set by `IssueDetector`. It is deliberately
not "this trace is in the Slow bucket": the bucket admits a trace by its total duration
(`slowTraceThresholdMs`, 1 s by default), the badge reports a single span at or above the
span threshold (100 ms by default). A 300 ms request with one 150 ms query shows the badge
under *All* and never appears under *Slow*; a 1.2 s request made of twelve 100 ms spans
appears under *Slow* and shows the badge too.

## How the embedded surfaces consume the shared sheets

The trace-detail overlay renders into its own shadow root
(`element.attachShadow({mode: 'open'})`) and calls:

```js
attachSharedStyles(shadowRoot, hostElement, basePath, ownSheetHref);
```

The toolbar does not: its shadow root is declarative, written by `DevToolbarFilter` (see
`ToolbarShell`), which carries `tokens.css`, `base.css` and `toolbar.css` inline and links
all four. It has to be self-sufficient — a reader who has put Spring Security in front of
`/peekaboot/**` cannot load a linked sheet from there any more than they can load
`toolbar.js`, and the bar still has to render and say so.

`attachSharedStyles` links `tokens.css`, `base.css`, `components.css`, and (if given) the
surface's own sheet as `<link>` elements inside the shadow root. A linked sheet in a shadow root loads
asynchronously, so `attachSharedStyles` holds `hostElement.style.visibility = 'hidden'`
until every sheet has settled (`load` or `error`), then reveals it — this is the FOUC
guard: without it, the toolbar/overlay would flash unstyled (or, worse, briefly show
through) before its CSS arrives. A 1-second timeout races the `Promise.all` of loads, so
one blocked or 404'd stylesheet can never leave the host permanently invisible.

**Two caveats that bit during development:**

- `basePath` is concatenated into the sheet URLs unguarded
  (`` `${basePath}/ui/assets/${name}` ``) — pass it **without a trailing slash**, or the
  resulting URL gets a double slash and 404s.
- `attachSharedStyles` must be called **exactly once per shadow root**. It doesn't guard
  against re-entry: a second call appends a second set of `<link>` elements rather than
  replacing the first. Its one remaining call site makes this safe by construction —
  `trace-detail.js`'s `open()` always calls `close()` (which removes the whole host
  element) before creating a new host and shadow root — but a future call site needs the
  same discipline.

## Theme resolution

`theme.js` resolves the active theme as: the value stored under
`localStorage['peekaboot-theme']` (`'light'` or `'dark'`), falling back to the OS
preference (`prefers-color-scheme: dark`) when nothing is stored or storage is
unavailable. Because the dashboard, the toolbar, and the overlay are all served
same-origin, they share `localStorage` for free — the toolbar and overlay pick up
whatever the dashboard's theme toggle last wrote, with no message-passing needed.
`watchTheme(callback)` keeps a surface in sync afterwards: it listens for both the OS
preference changing and another tab/surface writing the storage key, and returns an
unsubscribe function.

`applyTheme(target, theme)` just does `target.setAttribute('data-theme', theme)`. The
dashboard applies it to `document.documentElement`; the toolbar and overlay apply it to
their shadow **host** element (not the shadow root, which has no attributes) — which is
exactly why the dark-mode selector in `tokens.css`/`base.css` is written
`:host([data-theme="dark"])` rather than plain `:host[data-theme="dark"]` doubled some
other way: the attribute lives on the host, and `:host()` is the functional form that
lets you match a condition on the host from inside its own shadow tree.

## Accessibility invariants

Each of these has been broken at least once and caught only in review. Keep them true:

- **Never use `--pk-warning`, `--pk-success`, `--pk-primary` or `--pk-info` as text color
  on the page background.** All four are tuned as fill colors; as text on `--pk-bg` they
  measure 2.9:1, 3.3:1, 2.6:1 and 3.7:1 — all under WCAG AA. Every one has a `-text`
  counterpart (`--pk-warning-text`, `--pk-success-text`, `--pk-primary-text`,
  `--pk-info-text`) tuned against `--pk-bg`, `--pk-bg-alt` *and* `--pk-bg-hover` in both
  themes; reach for those. `--pk-primary-text` is also what focus rings and the
  selected-tab underline use, so they clear 1.4.11's 3:1 for non-text UI.
  `--pk-danger` and `--pk-purple` are the two exceptions that are dark enough to double
  as text colors (4.8:1 and 5.7:1), which is why they have no `-text` variant.
- **A saturated fill (`--pk-success`, `--pk-warning`, `--pk-danger`, `--pk-primary`,
  `--pk-info`) needs its own on-colour token as foreground** — `--pk-on-success`,
  `--pk-on-warning`, `--pk-on-danger`, `--pk-on-primary`, `--pk-on-info` respectively.
  **Never `--pk-text-strong` or literal `white`/`#fff` on one of these fills** —
  `white`/`--pk-text-strong` measure 2.53:1 and, in another spot, ~2.3:1 in dark mode; the
  `--pk-on-*` tokens clear 4.5:1+ in both themes by construction. With a green brand every
  `--pk-on-*` is dark ink, in both themes — white on the green fill is 2.61:1.
- **`--pk-info` is not an alias for `--pk-primary`.** It was, while `--pk-primary` was a
  blue. With a green brand, an INFO pill filled with `--pk-primary` sits beside a green
  `--pk-success` UP pill and reads as the same state, so `--pk-info` is held ~47° (light)
  / ~64° (dark) off `--pk-success` in hue.
- **Interactive elements are real controls with `:focus-visible`**, not `div`/`role`
  approximations. In particular: a `role="button"` container must not wrap a focusable
  child (e.g. a link) — ARIA defines a button's children as presentational, so assistive
  tech can prune the nested control right out of the accessibility tree, even though nothing
  looks wrong visually. The fix is always the same shape: the container stays a plain
  element, a real `<button>` carries the primary action, and the other interactive element
  becomes the button's **sibling**, not its descendant. The toolbar's open button and the
  traces tab's trace item header are the two places this shape was needed — watch for it
  in any "make this row clickable" change.

  The trace-detail overlay's small controls — the gantt expand/collapse triangle, the SQL
  and logs toggles, the gantt event markers, the "show logs for all spans" link, the log
  span-filter cell and the span-filter clear — are all `<button>`s with the browser's
  button chrome reset away, not `<span>`s with click handlers, so each is reachable by
  keyboard. If a new control needs `cursor: pointer`, that is the smell: make it a
  `<button>` first.

- **A control whose only content is an icon needs an explicit `aria-label`, and the icon
  needs `aria-hidden="true"`.** `title` does *not* rescue it: text content outranks title
  in the accessible-name algorithm, so a bare glyph button is announced as "↻" or "×".
  When the control is stateful (pause/resume, theme, timezone), the label is updated in
  `main.js` next to the icon swap — a label that lies about state is worse than none.

- **Decorative emoji get `aria-hidden="true"`.** The dashboard's card icons would
  otherwise be read as part of the heading: "package Build", "seedling Spring".

- **Motion respects `prefers-reduced-motion`.** `base.css` cancels animations and
  collapses transitions under that preference. This matters more than it looks: the
  health dot's pulse and the toolbar's animated ellipsis are *infinite*, running for as
  long as the surface is open. Nothing animated is load-bearing — the spinner is always
  paired with "Loading data..." text — so cancelling motion never removes meaning.

- **A modal needs `inert` on its siblings, not just `aria-modal`.** `aria-modal="true"`
  hides the rest of the page from assistive tech but leaves Tab free to walk out of the
  dialog for a sighted keyboard user. `trace-detail.js` sets `inert` on every other
  `document.body` child on open (which also covers the dev toolbar, a sibling host) and
  releases it on close — *before* restoring focus to the invoker, since `focus()` on an
  inert element does nothing.

- **Interactive targets are at least 24x24 CSS px** (WCAG 2.2 §2.5.8). Measured, not
  computed from the CSS — `.pk-btn--small` looked like 24px on paper and rendered at 20.

## Ids the test suite depends on

Renaming any of these silently breaks roughly a dozen Playwright test classes under
`peekaboot-testing-app`. If you must rename one, grep `peekaboot-testing-app/src/test`
first.

| Id / selector | Why it matters |
|---|---|
| `#loading` | `PlaywrightTestBase.openDashboard()` waits for this to become hidden as the app's readiness signal (set only after `fetchData()` → `renderData()` completes). |
| `#build-info` | Same helper then waits for `#build-info > *, #error:not(.hidden)` — positive proof of a real render, since `#loading` also hides on the failure path. |
| `#error` and its `.message` | `openDashboard()` checks `#error` visibility and reads `.message`'s text to fail fast with the actual error instead of hanging on a later selector wait. |
| the literal `hidden` class | Used throughout (`.hidden { display: none !important; }` in `dashboard.css`) as the state class Playwright's visibility waits key off. |
| `#machine-info` | `OverviewMachineIT` waits on and asserts the Machine card's rows here, and checks that `#jvm-defaults-info` carries no second processor-count row. |
| `#jvm-defaults-card` and `data-datasource` | `OverviewMachineIT` asserts the first datasource card (marked `data-datasource` by `overview.js`) directly follows the JVM Defaults card in the Overview grid. |
| `#traces-bucket` and `data-bucket` | The All/Errors/Slow bucket filter buttons; tests select and click by `[data-bucket="..."]`. |
| `#<tabId>-tab` panel convention | Each dashboard tab's content lives in a `<section id="<id>-tab">`; `main.js`'s `renderTab()` looks it up by this exact id, and tests wait on `#<tabId>-tab.active`. |
| `data-tab` | The tab-strip button attribute (`.pk-tab[data-tab="<id>"]`) that `tabStrip()` and `main.js` both key off for selection state. |

## The collapsed bar's trace fetch schedule

`toolbar.js` doesn't poll until the trace looks complete; it makes four fixed attempts —
at 250ms, 500ms, 1s and 3s after the previous one — and always runs all four, fetching
`/api/traces/{traceId}/insights` each time. Every attempt runs (rather than stopping the
first time a trace looks finished) so a span that ends after the root — an `@Async`
continuation, a streamed body — still reaches the bar. The last attempt lands at 4.75s
after the response finished; with `peekaboot-dev-toolbar-defaults.yml`'s 200ms span export
delay (see `docs/ARCHITECTURE.md` — *Default Properties*), a trace still absent by then
isn't coming, and the bar falls back to a pending placeholder rather than leaving a
spinner up forever. A response that arrived but was empty (a 404, or `rootSpan` missing)
leaves whatever the previous render already showed standing.

## `application-test.yml`: the export delay

`peekaboot-testing-app/src/test/resources/application-test.yml` sets
`management.opentelemetry.tracing.export.schedule-delay: 50ms` — lower even than
`peekaboot-dev-toolbar-defaults.yml`'s own 200ms default, which would otherwise apply here
since this profile sets `peekaboot.dev-toolbar: true` explicitly. Playwright tests open the
trace overlay immediately after page load, well inside either window, so without the 50ms
override the overlay's spans/queries/logs tabs would assert against an empty trace that
hadn't reached the `TraceStore` yet.

## How to add a dashboard tab

1. Create `dashboard/tabs/<id>.js` exporting:
   - `export const id = '<id>';`
   - `export const label = 'Display Name';`
   - `export function render(container, data, context) { ... }` — `context` is
     `{client, locale, timeZone, navigate, features, unmaskRequested, toggleUnmask,
     urlParams, urlIsAuthoritative, setUrlParams, traceUrlState}`, built by `main.js`'s
     `currentContext()`:
     - `unmaskRequested` / `toggleUnmask()` — whether the current payload was fetched
       with real values instead of `"******"`, and the shared flip that re-fetches (see
       `shared/unmask-control.js`); one state for every tab, never persisted.
     - `urlParams` — the active tab's own query params, freshly re-parsed from the hash.
     - `urlIsAuthoritative` — true only for a render triggered by a genuine hash change
       (deep link, Back/Forward, a hand-edited hash), as opposed to a programmatic tab
       switch; a tab's reconcile logic uses this to tell "the user asked for exactly this
       URL, including a bare one" apart from a tab-strip click's own bare hash push.
     - `setUrlParams(params)` — replaces the active tab's own query params; a no-op while
       a detail segment (the trace overlay) is open, since the params slot then belongs to
       the overlay instead (see `shared/url-filter.js` and `traces.js`'s `reconcileWithUrl`
       for the two halves of that rule).
     - `traceUrlState(traceId)` — builds the `{initial, update}` urlState object
       `openTraceDetail` expects, so a trace opened via a tab's own click-to-open path gets
       the same URL sync as one opened through a deep link.
   - optionally `export function isAvailable(data, features) { ... }` — gates whether the
     tab's strip button is shown at all (see `meters.js`/`traces.js` for real examples
     gating on a feature flag).
   - optionally `export function applyFilter(payload) { ... }` — lets another tab jump
     here with a pre-selected filter via `context.navigate(id, detail, payload)` (see
     `traces.js`).

   A tab that is a filterable list of collapsible groups builds on
   `shared/filtered-group-tab.js` instead of hand-rolling the shell — see `config.js` for
   the smallest example and `loggers.js` for one with a second filter control.
2. In `dashboard/main.js`, `import * as <name> from './tabs/<id>.js';` and add `<name>`
   to the `TABS` array. `main.js` never renders domain data itself — it only decides
   which tab module gets the fetched payload, so this is the only wiring the file needs.
3. In `dashboard/index.html`, add the tab strip button and the content panel by hand —
   `TABS` in `main.js` drives *rendering*, but the strip button and panel markup are
   static HTML, not generated:
   - `<button class="pk-tab" role="tab" id="<id>-tab-btn" data-tab="<id>" aria-controls="<id>-tab" aria-selected="false">Display Name</button>`
     inside `#main-tabs` (add `hidden` to the class list if the tab is meant to start
     hidden pending `isAvailable`).
   - `<section class="pk-tab-panel" id="<id>-tab" role="tabpanel" aria-labelledby="<id>-tab-btn">...</section>`
     inside `<main>`, with whatever container elements `render()` expects to find via
     `container.querySelector(...)`.

   `renderTab()` in `main.js` looks up the tab's button by `.pk-tab[data-tab="<id>"]` and
   its panel by `#<id>-tab`; without both existing in the HTML, the tab is registered but
   never rendered or shown, whatever `TABS` says.

