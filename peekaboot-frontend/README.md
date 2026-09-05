# Peekaboot Frontend

Static resources served from `/peekaboot/ui/`, backing three UI surfaces that share one
design system:

- **Dashboard** (`dashboard/`). The standalone app-insights page.
- **Dev toolbar** (`toolbar/`). Rendered into host-application pages by `DevToolbarFilter`
  and enhanced in place by `toolbar.js`, inside a shadow root so host-page CSS can't reach
  it and vice versa.
- **Trace-detail overlay** (`trace-detail/`). A full-screen dialog, also shadow-rooted,
  opened from the dashboard or the toolbar.

No build step. Plain ES modules and CSS, served as-is.

```
META-INF/peekaboot/ui/
├── assets/          tokens.css, base.css, components.css: the shared design system
│                    theme-boot.js: the dashboard's pre-paint theme stamp
│                    favicon-16/32.png, logo-mark.png, logo-mark-dark.png: the icon set
├── shared/          17 modules used by two or more surfaces; see the inventory below
├── dashboard/       index.html, dashboard.css, main.js, tabs/*.js  (10 tabs, plus the
│                    Insights tab's own insights-store.js, insights-chart.js,
│                    insights-markers.js and insights-colors.js)
├── trace-detail/    trace-detail.css, trace-detail.js, tabs/*.js   (4 tabs)
├── toolbar/         toolbar.css, toolbar.js
└── vendor/          uplot/: the only third-party code, loaded on demand (see below)
```

The Insights tab charts with [uPlot](https://github.com/leeoniya/uPlot), vendored under
`vendor/uplot/` (MIT, version pinned in `VERSION`) and the one exception to "plain ES
modules". `insights-chart.js` injects the script and stylesheet the first time a chart has
to be drawn, so a dashboard that never opens the tab never loads it.

## The three shared layers

`assets/` holds three stylesheets, each with a distinct job, loaded in this order by every
surface:

1. **`tokens.css`**. Custom properties only. Every colour, space, radius and type size the
   other sheets use resolves through a `--pk-*` token, which is what keeps a literal from
   settling into a component rule. Light palette on `:root`, dark under
   `[data-theme="dark"]`.
2. **`base.css`**. The reset (`box-sizing`) and bare element defaults (`body`, `mark`). No
   component classes.
3. **`components.css`**. The `.pk-*` primitives (badge, group, kv row, meter, button, tab
   strip, empty state, spinner) every surface's own CSS builds on. A surface stylesheet
   (`dashboard.css`, `toolbar.css`, `trace-detail.css`) only adds surface-specific chrome,
   never a second copy of a primitive. A variant one surface needs becomes a modifier here
   (`.pk-table--kv`, the overlay's key/value table).

### The doubled-selector mechanism

`tokens.css` and `base.css` each declare their rules twice, which is what lets one file
serve three DOM contexts:

```css
:root, :host { --pk-bg: #ffffff; /* ... */ }
[data-theme="dark"], :host([data-theme="dark"]) { --pk-bg: #0d1117; /* ... */ }
```

`:root` matches the dashboard's own document. `:host` matches the shadow root of an element
these same `<link>` tags are loaded into. The identical file works unmodified whether it is
linked into `dashboard/index.html`'s `<head>` or into the toolbar's or overlay's shadow
root. No surface-specific variant, no build step to generate one.

The type scale is the one place the two deliberately differ. `:root` keeps it in `rem`, so
the dashboard follows a reader's root-size preference; a `:host`-only block below pins the
same sizes in `px`, because `rem` resolves against the *host document's* root font size and
the toolbar and overlay live in pages Peekaboot does not own. A host with the common
`html { font-size: 62.5% }` reset would render the bar at 7.5px. The two blocks move
together: change `--pk-text-*` in one and change it in the other, or the embedded surfaces
drift away from the dashboard.

### Two token pairs the file does not explain

| Token | What it paints |
|---|---|
| `--pk-mark-bg` / `--pk-on-mark` | The search highlight's fill and its ink (`base.css`'s `mark`). It needs both. With one missing the highlight is unreadable, not merely off-theme. |
| `--pk-danger-tint` | The error banner's and the failed-task block's wash: each theme's own `--pk-danger` at 4% alpha. |

Every rule reading one of them repeats the light-theme literal as its `var()` fallback:
`base.css`'s `mark`, `dashboard.css`'s `.pk-error-banner` and `.pk-task__exception`. A token
that goes missing then costs the dark palette for that rule and nothing else, rather than
leaving the element with no fill or no ink.

## The icon set

`assets/` holds four PNGs, all derived from one piece of source artwork: the simplified
Peekaboot mark (green hexagon, slate magnifier, green bars). The detailed logo is
deliberately *not* used. Below roughly 96px its interior detail (list rows, line chart,
gloss) turns to noise, and the two places the UI shows a logo are 26px and 18px.

| File | Used by |
|---|---|
| `favicon-16.png`, `favicon-32.png` | `<link rel="icon">` in `dashboard/index.html`. |
| `logo-mark.png` (96px) | `.pk-header__logo` and the toolbar's dashboard link, light theme. |
| `logo-mark-dark.png` (96px) | The same two, dark theme. |

There are two variants because the mark is two-tone. Its slate magnifier (`#263238`)
measures 13.2:1 on white and 1.4:1 on the dark theme's `--pk-bg`, where it disappears, so
`logo-mark-dark.png` is the same artwork with that slate recoloured to a light neutral.
Both surfaces swap it with a CSS `background-image` override, no JavaScript:

```css
[data-theme="dark"] .pk-header__logo   { background-image: url('../assets/logo-mark-dark.png'); }  /* dashboard */
:host([data-theme="dark"]) .pk-toolbar__link { background-image: url('../assets/logo-mark-dark.png'); }  /* shadow root */
```

The toolbar's `url()` resolves against `toolbar.css`'s own URL, which is why it works from
inside a shadow root without knowing `basePath`.

To regenerate from new source artwork (the masters live outside this repo, alongside it in
`peekaboot-org/assets/`), crop to the bounding box, centre it in a square with ~6% padding,
and resample with Lanczos. The anti-aliasing lives in the alpha channel with full-strength
RGB underneath. A fuzzy colour replacement therefore recolours cleanly, without fringing.

```sh
magick peekaboot-logo-favicon.png -crop 909x1015+173+105 +repage \
       -background none -gravity center -extent 1076x1076 master.png
magick master.png -background none -filter Lanczos -resize 32x32 -strip favicon-32.png
magick master.png -fuzz 20% -fill '#e6edf3' -opaque '#263238' master-dark.png   # dark variant
```

## `shared/` module inventory

| Module | Exports |
|---|---|
| `api.js` | `createClient({basePath})`, a fetch wrapper; a per-path generation counter makes an overtaken response resolve to `null` instead of racing a newer one. `BASE_PATH`, the default `basePath`, read off this module's own URL (`<context-path>/peekaboot`), so the dashboard and the overlay it opens follow a `server.servlet.context-path` without being told; the toolbar gets the same value from the server in its data blob. |
| `components.js` | `badge`, `badgeHtml`, `kvRow`, `group`, `meter`, `groupList`, `expandedKeys`, `tabStrip`, `table`, `emptyState`, `emptyStateHtml`, `loadingBlock`. The JS builders behind the `.pk-*` primitives; the `*Html` variants serve the surfaces that build their markup as strings. `tabStrip`'s `panel` option wires a runtime-built strip to its tabpanel (`aria-controls`, `aria-labelledby`). |
| `copyable.js` | `copyableIdHtml`, `copyableId`, `bindCopyables`. The click-to-copy trace/span id control, as an HTML string or a detached element, with one delegated click listener per root (document or shadow root). |
| `filtered-group-tab.js` | `filteredGroupTab({inputId, listId, select, filterGroup, key, header, items, extraTop, emptyMessage, noMatchMessage, urlFilter, decorate, afterRender, fetchData, loadingMessage, fetchErrorMessage})`. The shell of a dashboard tab that shows a filterable list of collapsible groups (module state, the filter input wired once, URL reconciliation, expansion restore, empty states); `config.js`, `environment.js`, `loggers.js` and `meters.js` are built on it and supply only what differs. `fetchData(context)` is the hook for a tab whose data comes from its own endpoint instead of the shared payload (`meters.js`); it is called only while the tab is active, with loading and error states handled by the shell. |
| `format.js` | `formatDurationMs`, `formatLongDuration`, `formatInterval`, `formatBytes`, `formatHosts`, `formatDateTime`, `formatTimeOfDay`, `formatCount`, `formatMetricValue`, `formatTileValue`. |
| `http-status.js` | `statusLabel` (`404` → `"404 Not Found"`), `statusVariant` (the badge tier per response family). |
| `markup.js` | `escapeHtml`, `highlightText`, `MASK_LITERAL`, the fallback for the backend's masked-value literal (`Features.maskLiteral`, `"******"`), used only by the surfaces that never load `/api/features` (the dev toolbar and the overlay it opens). |
| `unmask-control.js` | `renderUnmaskControl(slot, context)`, the Environment/Config "Show secrets" toggle. Renders nothing into an empty slot unless `context.features.unmaskingEnabled` is true; the frontend does not decide what is sensitive, only whether the reveal control can work at all. |
| `root-actions.js` | `ROOT_ACTION_TYPES`, `rootActionIcon`, `rootActionLabel`. The icon and label map for a trace's root action type (HTTP request, scheduled job, and so on). |
| `severity.js` | `durationSeverity(ms, features)`, `querySeverity(ms, features)`, `threshold(features, key)`, `DEFAULT_THRESHOLDS`, `issueSeverity(issues)`, `ISSUE_TYPES`, `LOG_LEVELS`, `logLevelVariant(level)`, `healthSeverity(status)`. The one place a duration, a span's issues, a log level or a health status is turned into a colour. See *Thresholds and the SLOW badge* below. |
| `shadow-styles.js` | `attachSharedStyles(shadowRoot, hostElement, basePath, ownSheetHref)`. Links the shared sheets (plus the surface's own) into a shadow root; see below. |
| `span-names.js` | `buildSpanNames(rootSpan)`. A spanId → name lookup, used by the overlay's Logs tab to name the span each log row belongs to. |
| `storage.js` | `readSetting`, `writeSetting`. Guarded `localStorage` access for per-browser settings; a blocked store reads as `null` and writes are dropped instead of throwing during module evaluation. |
| `theme.js` | `resolveTheme`, `applyTheme`, `storeTheme`, `watchTheme`. |
| `trace-stats.js` | `traceStatParts(trace, features)`. A trace's stat line (query count with total query time, error and warning log counts) as detached elements; the Traces tab's rows and the dev toolbar's bar both render it, so neither can drift in wording or colouring. |
| `url-state.js` | `parseAppHash`, `buildAppHash`, `pushAppHash`, `replaceAppHash`. The `#<tab>[/<detail>[/<subview>]][?<query>]` hash routing format; structural segments (tab, detail) push a history entry, subview and params replace it. |
| `url-filter.js` | `reconcileFilterWithUrl(context, urlKeys, {seed, hasNonDefaultState, writeBack})`, the shared URL-authoritative-vs-current-state direction logic behind every dashboard tab's filter-URL reconciliation. `reconcileTextFilter`/`writeTextFilter(input, context)`, the single-text-input case built on it (`config.js`, `environment.js` and `meters.js`'s own filter; `loggers.js` composes the lower-level helper directly for its q+checkbox pair, `traces.js` for bucket/type/op, `insights.js` for its level/percentiles/restarts/panels params and `lifecycle.js` for its page). |

## URL state (deep links)

Every dashboard view is addressable: `#<tab>[/<detail>[/<subview>]][?<params>]`
(`shared/url-state.js`). Structural segments (tab, detail) push a history entry. Subview
and params are written with `replaceState`, so a filter keystroke or an overlay tab switch
never adds a Back stop. Opening a URL restores the state below; changing that state
rewrites the URL in place. `shared/url-filter.js` holds the "URL vs. current state"
direction rule every tab applies: a tab-strip click's own bare hash push must not clear a
filter, a hand-edited bare hash must.

| View | URL params | Restored / written state |
|---|---|---|
| `#overview` | none | No view state of its own. |
| `#insights` | `level`, `percentiles`, `restarts`, `panels` | The global aggregation level, a configured level index; the first level is the default and stays out of the URL. The Percentiles checkbox (`percentiles=1` only while on) and the Restarts checkbox (`restarts=0` only while off). Per-panel level overrides (`panels=<id>:<level>,…`), only for panels pinned off the global level. |
| `#lifecycle` | `page` | The pager's 1-based page. Page one stays out of the URL; an out-of-range value clamps to the last page and the URL is corrected. |
| `#traces` | `bucket`, `type`, `op` | Bucket (`all`/`errors`/`slow`), comma-separated root action types, root operation. |
| `#traces/<traceId>` | none | Opens the trace overlay on its Spans tab. |
| `#traces/<traceId>/<subview>` | The overlay tab's own filters | `request`/`spans`/`queries`/`logs`; the Logs tab carries `q`, `level`, `span`. |
| `#meters` | `q` | The text filter. |
| `#environment` | `q` | The text filter. |
| `#flyway` | none | No filter. |
| `#loggers` | `q`, `configured` | The text filter and the configured-only checkbox (`configured=1`). |
| `#config` | `q` | The text filter. |
| `#scheduled-tasks` | none | No filter. Group expansion is deliberately not URL state, on any tab. |

Theme, locale and timezone are per-browser settings (`localStorage`), never URL state. A
shared link must not impose the sender's display preferences on the reader. An invalid
param value (unknown bucket, root action type, level, log level, page, checkbox flag or
panel override) falls back to its default instead of reaching the backend or filtering
invisibly, and the URL is rewritten to the state that restored. A lower-case `type` is
folded the way the backend folds it.

### Cross-links in the trace overlay

The overlay's tabs link into each other. A log row links to the span that wrote it, a query
span's gantt row links to its entry in the Queries tab, and each Queries entry links back to
its span row (`trace-detail.js`'s `goToSpan`/`goToQuery`). The Logs tab's span-name button
is the older third link, filtering the log list to a span. A jump switches the overlay tab
the way the strip would (`replaceState`, params reset), scrolls to the target, moves keyboard
focus onto it and marks it with the temporary `.pk-jump-flash` highlight. The anchors are
`data-span-id` on the gantt rows and on `.pk-query-item` (`QueryInfo.spanId`).

## Thresholds and the SLOW badge

Every number the frontend colours a duration by comes from the backend, once, over
`GET /peekaboot/api/features` (the `Features` record). Four of them: `slowSpanThresholdMs`
and `verySlowSpanThresholdMs` (`peekaboot.ui.tracing.*`, what `IssueDetector` raises SLOW
and VERY_SLOW at), `slowQueryThresholdMs` (SLOW_QUERY) and `slowTraceThresholdMs`
(`peekaboot.tracing.slow-trace-threshold-ms`, the Slow bucket's admission threshold, `null`
while tracing is off). The dashboard hands its `features` to every tab and to the overlay
it opens (`openTraceDetail(traceId, {locale, timeZone, features})`). The dev toolbar and
the overlay it opens are the one path with no features in hand; there `severity.js`'s
`DEFAULT_THRESHOLDS` mirrors the backend defaults under the same names, and
`SharedModuleIT` pins them to the backend properties' defaults.

Every comparison is at-or-above (`>=`), on both sides of the wire. `IssueDetector` raises
SLOW/VERY_SLOW/SLOW_QUERY at `duration >= threshold`, and `durationSeverity()`/
`querySeverity()` colour with the same comparison. A 100 ms span is already SLOW at the
default thresholds, a 50 ms query already slow. Where a span's own issues are in hand,
`issueSeverity(span.issues)` is the backend's verdict and colours the gantt duration cells;
`durationSeverity()` re-derives a severity only for durations no issue describes, meaning a
trace's total, a trace's total query time and a Flyway migration's execution time. The
Queries tab's per-query SLOW label uses `querySeverity()`, the threshold behind the
backend's SLOW_QUERY issue (`slowQueryThresholdMs`, 50 ms by default), never the span
thresholds.

The Traces tab's SLOW badge means "some span in this trace carries a SLOW or VERY_SLOW
issue". That is the backend's per-trace `slow` flag, set by `IssueDetector`, and it is
deliberately not "this trace is in the Slow bucket". The bucket admits a trace by total
duration (`slowTraceThresholdMs`, 1 s by default); the badge reports a single span at or
above the span threshold (100 ms). A 300 ms request with one 150 ms query shows the badge
under *All* and never appears under *Slow*. A 1.2 s request made of twelve 100 ms spans
appears under *Slow* and shows the badge too.

## How the embedded surfaces consume the shared sheets

The trace-detail overlay renders into its own shadow root
(`element.attachShadow({mode: 'open'})`) and calls:

```js
attachSharedStyles(shadowRoot, hostElement, basePath, ownSheetHref);
```

The toolbar does not. Its shadow root is declarative, written by `DevToolbarFilter` (see
`ToolbarShell`), which carries `tokens.css`, `base.css` and `toolbar.css` inline and links
all four. It has to be self-sufficient: a reader who has put Spring Security in front of
`/peekaboot/**` cannot load a linked sheet from there any more than they can load
`toolbar.js`, and the bar still has to render and say so.

`attachSharedStyles` links `tokens.css`, `base.css`, `components.css`, and (if given) the
surface's own sheet into the shadow root as `<link>` elements. Those load asynchronously, so
it holds `hostElement.style.visibility = 'hidden'` until every sheet has settled (`load` or
`error`), then reveals it. That is the FOUC guard: without it the toolbar and overlay would
flash unstyled, or briefly show through, before their CSS arrives. A 1-second timeout races
the `Promise.all`, so one blocked or 404'd stylesheet can never leave the host invisible.

Two caveats that bit during development. `basePath` is concatenated into the sheet URLs
unguarded (`` `${basePath}/ui/assets/${name}` ``), so pass it without a trailing slash or
the URL gets a double slash and 404s. And `attachSharedStyles` must be called exactly once
per shadow root: it does not guard against re-entry, and a second call appends a second set
of `<link>` elements. Its one remaining call site is safe by construction, since
`trace-detail.js`'s `openTraceDetail()` always calls `closeTraceDetail()` (which removes
the whole host element) before creating a new host and shadow root. A future call site
needs the same discipline.

## Theme resolution

`theme.js` resolves the active theme as the value stored under
`localStorage['peekaboot-theme']` (`'light'` or `'dark'`), falling back to
`prefers-color-scheme: dark` when nothing is stored or storage is unavailable. All three
surfaces are served same-origin, so they share `localStorage` for free: the toolbar and
overlay pick up whatever the dashboard's theme toggle last wrote, with no message passing.
`watchTheme(callback)` keeps a surface in sync afterwards, listening for both the OS
preference and another tab's write, and returns an unsubscribe.

The dashboard stamps `data-theme` once more, before any of this. `assets/theme-boot.js`,
linked from the top of `index.html`'s `<head>`, performs the same resolution before the
stylesheets apply, because `main.js` is a module script and runs after first paint. Without
it a dark-theme reader saw the light palette flash on every load. It is the one piece of
theme logic `theme.js` does not own, and it is a classic, non-deferred script rather than an
inline block: a module would run too late, and an inline block is dropped by a host whose
CSP omits `script-src 'unsafe-inline'`.

`applyTheme(target, theme)` does `target.setAttribute('data-theme', theme)` and nothing
else. The dashboard applies it to `document.documentElement`; the toolbar and overlay apply
it to their shadow host element, since the shadow root itself has no attributes. That is why
the dark-mode selector is written `:host([data-theme="dark"])`, the functional form that
matches a condition on the host from inside its own shadow tree.

Both theme blocks in `tokens.css` also declare `color-scheme`: `light` on `:root, :host`,
`dark` on the dark block. That is what makes native widgets follow the theme. Without it
scrollbars, the `<select>` popup, checkboxes and the caret stay light on a dark page.

## Accessibility invariants

Each of these has been broken at least once and caught only in review. Keep them true.

### Colour tokens

`tokens.css` gives each colour two roles, tuned against different grounds:

| Role | Tokens | Drawn on |
|---|---|---|
| Fill | `--pk-primary`, `--pk-success`, `--pk-warning`, `--pk-info`, `--pk-danger`, `--pk-purple`, `--pk-danger-soft` | Backgrounds: badge fills, buttons, banners. Each carries its own `--pk-on-*` ink on top. |
| Text | `--pk-primary-text`, `--pk-success-text`, `--pk-warning-text`, `--pk-info-text` | The page background (`--pk-bg`, `--pk-bg-alt`, `--pk-bg-hover`): text, focus rings, borders, the selected-tab underline. |

**Change a colour's whole trio together: the fill token, its `--pk-on-*` ink, and its
`-text` variant.** Change only the fill and every fill keeps ink tuned for the old colour
while every text usage keeps rendering in the old colour. It is the accessibility
regression that survives review, because it looks right everywhere the colour is a
background and is silently unreadable everywhere it is text. `--pk-mark-bg`/`--pk-on-mark`
is the same kind of pair. `tokens.css` records the ratio behind every pairing as a trailing
comment; re-measure and update those comments when a value moves.

**Never use `--pk-warning`, `--pk-success`, `--pk-primary` or `--pk-info` as text on the
page background.** In light mode the brand green `--pk-primary` (`#66b327`) is a
mid-lightness fill. White on it is 2.6:1, well under AA, and contrast is symmetric, so the
green is equally unreadable as text on white. That is why `--pk-primary-text` (`#447718`,
5.39:1 on `--pk-bg`) exists as a separate, darker green, and why it also carries focus rings
and the selected-tab underline, which clear 1.4.11's 3:1 for non-text UI. `--pk-success` is
3.3:1 as text. `--pk-info` and `--pk-warning` pass by the numbers (5.36:1 and 4.83:1)
because their light fills sit in the deep tier, but they are ladder-tuned and may move
again, so text still goes through the `-text` token. `--pk-danger` and `--pk-purple` are the
two dark enough to serve both roles (5.31:1 and 5.70:1, down to 4.73:1 for danger on
`--pk-bg-hover`, still AA), which is why neither has a `-text` variant.

**Never put `--pk-text-strong` or a literal `white`/`#fff` on a saturated fill.** Each fill
has an `--pk-on-*` counterpart that clears 4.5:1 in both themes by construction;
`white`/`--pk-text-strong` measure 2.53:1 and, in another spot, ~2.3:1 in dark mode. The
inks do not follow one rule. In light mode `--pk-warning`, `--pk-danger`, `--pk-info` and
`--pk-purple` sit deep enough to carry white (4.83:1, 5.31:1, 5.36:1, 5.70:1) while the
green `--pk-primary` and `--pk-success` take dark ink (7.25:1, 5.74:1). In dark mode every
saturated fill takes dark ink. `--pk-on-danger-soft` inverts in both directions, because
that fill recedes rather than lightens so the softer 4xx tier cannot out-glow the full-error
pill.

**`--pk-info` is not an alias for `--pk-primary`.** It was, while `--pk-primary` was a blue.
With a green brand, an INFO pill filled with `--pk-primary` sits beside a green
`--pk-success` UP pill and reads as the same state, so `--pk-info` is held ~51° (light) /
~64° (dark) off `--pk-success` in hue, and in light additionally a tier deeper.

### Controls and markup

- **Interactive elements are real controls with `:focus-visible`**, not `div`/`role`
  approximations. In particular a `role="button"` container must not wrap a focusable child
  such as a link: ARIA defines a button's children as presentational, so assistive tech can
  prune the nested control right out of the accessibility tree while nothing looks wrong
  visually. The fix is always the same shape. The container stays a plain element, a real
  `<button>` carries the primary action, and the other interactive element becomes the
  button's sibling rather than its descendant. The toolbar's open button and the traces tab's
  trace item header needed it; watch for it in any "make this row clickable" change.

  The trace-detail overlay's small controls are all `<button>`s with the browser's button
  chrome reset away, so each is reachable by keyboard. That covers the gantt
  expand/collapse triangle, the SQL and logs toggles, the gantt event markers, the "show
  logs for all spans" link, the log span-filter cell and the span-filter clear. A new
  control that needs `cursor: pointer` is the smell; make it a `<button>` first.

- **A control whose only content is an icon needs an explicit `aria-label`, and the icon
  needs `aria-hidden="true"`.** `title` does *not* rescue it: text content outranks title
  in the accessible-name algorithm, so a bare glyph button is announced as "↻" or "×".
  When the control is stateful (pause/resume, theme, timezone), the label is updated in
  `main.js` next to the icon swap. A label that lies about state is worse than none.

- **Decorative emoji get `aria-hidden="true"`.** The dashboard's card icons would otherwise
  be read as part of the heading: "package Build", "seedling Spring".

- **Motion respects `prefers-reduced-motion`.** `base.css` cancels animations and collapses
  transitions under that preference. The health dot's pulse and the toolbar's animated
  ellipsis are *infinite*, running as long as the surface is open. Nothing animated is
  load-bearing: the spinner is always paired with "Loading data..." text, so cancelling
  motion removes no meaning.

- **A modal needs `inert` on its siblings, not just `aria-modal`.** `aria-modal="true"`
  hides the rest of the page from assistive tech but leaves Tab free to walk out of the
  dialog for a sighted keyboard user. `trace-detail.js` sets `inert` on every other
  `document.body` child on open (which also covers the dev toolbar, a sibling host) and
  releases it on close *before* restoring focus to the invoker, since `focus()` on an inert
  element does nothing.

- **Interactive targets are at least 24x24 CSS px** (WCAG 2.2 §2.5.8). Measured, not
  computed from the CSS: `.pk-btn--small` looked like 24px on paper and rendered at 20.

## Ids the test suite depends on

Renaming any of these silently breaks roughly a dozen Playwright test classes under
`peekaboot-testing-app`. Grep `peekaboot-testing-app/src/test` before you do.

| Id / selector | Why it matters |
|---|---|
| `#loading` | `PlaywrightTestBase.openDashboard()` waits for this to become hidden as the app's readiness signal (set only after `fetchData()` → `renderData()` completes). |
| `#build-info` | Same helper then waits for `#build-info > *, #error:not(.hidden)`, positive proof of a real render, since `#loading` also hides on the failure path. |
| `#error` and its `.message` | `openDashboard()` checks `#error` visibility and reads `.message`'s text to fail fast with the actual error instead of hanging on a later selector wait. |
| the literal `hidden` class | Used throughout (`.hidden { display: none !important; }` in `dashboard.css`) as the state class Playwright's visibility waits key off. |
| `#machine-info` | `OverviewMachineIT` waits on and asserts the Machine card's rows here, and checks that `#jvm-defaults-info` carries no second processor-count row. |
| `#machine-net-tabs`, `#machine-net-ipv4[-btn]`, `#machine-net-ipv6[-btn]` and `.pk-machine-net__addr` | The Machine card's IPv4/IPv6 tab strip and its per-family address rows; `OverviewMachineIT` drives selection, keyboard navigation and the refresh-survival of the open family through these ids. |
| `#jvm-defaults-card` and `data-datasource` | `OverviewMachineIT` asserts the first datasource card (marked `data-datasource` by `overview.js`) directly follows the JVM Defaults card in the Overview grid. |
| `#traces-bucket` and `data-bucket` | The All/Errors/Slow bucket filter buttons; tests select and click by `[data-bucket="..."]`. |
| `#<tabId>-tab` panel convention | Each dashboard tab's content lives in a `<section id="<id>-tab">`; `main.js`'s `renderTab()` looks it up by this exact id, and tests wait on `#<tabId>-tab.active`. |
| `data-tab` | The tab-strip button attribute (`.pk-tab[data-tab="<id>"]`) that `tabStrip()` and `main.js` both key off for selection state. |

## The collapsed bar's trace fetch schedule

`toolbar.js` does not poll until the trace looks complete. It makes four fixed attempts, at
250ms, 500ms, 1s and 3s after the previous one, fetching `/api/traces/{traceId}/insights`
each time. All four always run, rather than stopping the first time a trace looks finished,
so a span that ends after the root still reaches the bar: an `@Async` continuation, or a
streamed body. The last attempt lands 4.75s after the response finished. With
`peekaboot-dev-toolbar-defaults.yml`'s 200ms span export delay (see `docs/ARCHITECTURE.md`,
*Default Properties*), a trace still absent by then is not coming, and the bar falls back to
a pending placeholder rather than leaving a spinner up forever. A response that arrived but
was empty (a 404, or `rootSpan` missing) leaves the previous render standing.

`ToolbarLateSpanIT` is timed against this ladder: `LateSpanFixture.LateSpanController.LATE_WORK`
is arithmetic over the four attempts and the test profile's export delay, so moving an
attempt means redoing that arithmetic.

## `application-test.yml`: the export delay

`peekaboot-testing-app/src/test/resources/application-test.yml` sets
`management.opentelemetry.tracing.export.schedule-delay: 50ms`, lower even than
`peekaboot-dev-toolbar-defaults.yml`'s 200ms default, which would otherwise apply here since
this profile sets `peekaboot.dev-toolbar: true` explicitly. Playwright tests open the trace
overlay immediately after page load, well inside either window. Without the override the
overlay's spans, queries and logs tabs would assert against a trace that had not reached the
`TraceStore` yet.

## How to add a dashboard tab

1. Create `dashboard/tabs/<id>.js` exporting `id`, `label` and
   `render(container, data, context)`. `context` comes from `main.js`'s `currentContext()`
   and carries `{client, locale, timeZone, navigate, openTrace, features, active,
   unmaskRequested, toggleUnmask, urlParams, urlIsAuthoritative, setUrlParams}`. The five
   that need explaining:
   - `active`: whether the tab being rendered is the visible one. Every tab is rendered on
     the 30s cycle whichever is showing, so a tab only reconciles its filter with
     `urlParams` (which reflect the URL's tab, not this one) and only fetches its own data
     while `active`. `main.js` renders it again the moment it is switched to.
   - `unmaskRequested` / `toggleUnmask()`: whether the current payload was fetched with
     real values instead of `"******"`, and the shared flip that re-fetches (see
     `shared/unmask-control.js`). One state for every tab, never persisted.
   - `urlIsAuthoritative`: true only for a render triggered by a genuine hash change (deep
     link, Back/Forward, a hand-edited hash), not a programmatic tab switch. A tab's
     reconcile logic uses it to tell "the user asked for exactly this URL, including a bare
     one" apart from a tab-strip click's own bare hash push.
   - `setUrlParams(params)`: replaces the active tab's own query params. A no-op while a
     detail segment (the trace overlay) is open, since the params slot then belongs to the
     overlay (see `shared/url-filter.js` and `traces.js`'s `reconcileWithUrl`).
   - `openTrace(traceId)`: opens the trace overlay the way a `#traces/<id>` deep link does
     (hash push, URL sync of the overlay's own tabs and filters, hash cleanup on close), so
     a tab's click-to-open path cannot drift from the deep-link one.

   Two optional exports: `isAvailable(data, features)` gates whether the tab's strip button
   is shown at all (`meters.js`, `traces.js` gate on a feature flag), and
   `applyFilter(payload, context)` lets another tab jump here with a pre-selected filter via
   `context.navigate(id, detail, payload)` (see `traces.js`). A tab that is a filterable list
   of collapsible groups builds on `shared/filtered-group-tab.js` rather than hand-rolling
   the shell: `config.js` is the smallest example, `loggers.js` one with a second control.
2. In `dashboard/main.js`, `import * as <name> from './tabs/<id>.js';` and add `<name>` to
   the `TABS` array. `main.js` never renders domain data itself; it only decides which tab
   module gets the fetched payload, so this is the only wiring the file needs.
3. In `dashboard/index.html`, add the strip button and the content panel by hand. `TABS`
   drives *rendering*; this markup is static:
   - `<button class="pk-tab" role="tab" id="<id>-tab-btn" data-tab="<id>" aria-controls="<id>-tab" aria-selected="false">Display Name</button>`
     inside `#main-tabs` (add `hidden` to the class list if the tab is meant to start
     hidden pending `isAvailable`).
   - `<section class="pk-tab-panel" id="<id>-tab" role="tabpanel" aria-labelledby="<id>-tab-btn">...</section>`
     inside `<main>`, with whatever container elements `render()` expects to find via
     `container.querySelector(...)`.

   `renderTab()` looks up the button by `.pk-tab[data-tab="<id>"]` and the panel by
   `#<id>-tab`. Without both in the HTML, the tab is registered but never rendered or
   shown, whatever `TABS` says.
