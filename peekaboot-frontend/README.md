# Peekaboot Frontend

Static resources served from `/peekaboot/ui/`, backing three separate UI surfaces that
share one design system:

- **Dashboard** (`dashboard/`) — the standalone app-insights page.
- **Dev toolbar** (`toolbar/`) — injected into host-application pages by `DevToolbarFilter`,
  rendered inside a shadow root so host-page CSS can't reach it and vice versa.
- **Trace-detail overlay** (`trace-detail/`) — a full-screen dialog, also shadow-rooted,
  opened from either the dashboard or the toolbar.

No build step. Plain ES modules and CSS, served as-is.

```
static/peekaboot/ui/
├── assets/          tokens.css, base.css, components.css — the shared design system
├── shared/          api.js, components.js, format.js, markup.js, root-actions.js,
│                    severity.js, shadow-styles.js, span-names.js, theme.js
├── dashboard/       index.html, dashboard.css, main.js, tabs/*.js  (8 tabs)
├── trace-detail/    trace-detail.css, trace-detail.js, tabs/*.js   (4 tabs)
└── toolbar/         toolbar.css, toolbar.js
```

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
   one of these; it only adds surface-specific chrome.

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

## `shared/` module inventory

| Module | Exports |
|---|---|
| `api.js` | `createClient({basePath})` — fetch wrapper; a per-path generation counter makes an overtaken response resolve to `null` instead of racing a newer one. |
| `components.js` | `badge`, `kvRow`, `group`, `meter`, `groupList`, `expandedKeys`, `tabStrip` — the JS builders behind the `.pk-*` primitives. |
| `format.js` | `formatDurationMs`, `formatBytes`, `formatHosts`, `formatDateTime`, `formatTimeOfDay`. |
| `markup.js` | `escapeHtml`, `highlightText`, `isSensitiveKey`. |
| `root-actions.js` | `ROOT_ACTION_TYPES`, `rootActionIcon`, `rootActionLabel` — the icon/label map for a trace's root action type (HTTP request, scheduled job, …). |
| `severity.js` | `SLOW_MS`, `VERY_SLOW_MS`, `durationSeverity`, `healthSeverity` — the one place duration and health thresholds are decided. |
| `shadow-styles.js` | `attachSharedStyles(shadowRoot, hostElement, basePath, ownSheetHref)` — links the shared sheets (plus the surface's own) into a shadow root; see below. |
| `span-names.js` | `buildSpanNames(rootSpan)` — spanId → name lookup, shared by the overlay's Spans and Logs tabs. |
| `theme.js` | `THEME_STORAGE_KEY`, `resolveTheme`, `applyTheme`, `storeTheme`, `watchTheme`. |

## How the embedded surfaces consume the shared sheets

The toolbar and the trace-detail overlay each render into their own shadow root
(`element.attachShadow({mode: 'open'})`) and call:

```js
attachSharedStyles(shadowRoot, hostElement, basePath, ownSheetHref);
```

This links `tokens.css`, `base.css`, `components.css`, and (if given) the surface's own
sheet as `<link>` elements inside the shadow root. A linked sheet in a shadow root loads
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
  replacing the first. Both current call sites make this safe by construction —
  `toolbar.js`'s bootstrap only runs if `#peekaboot-toolbar-host` isn't already in the
  DOM, and `trace-detail.js`'s `open()` always calls `close()` (which removes the whole
  host element) before creating a new host and shadow root — but a future call site needs
  the same discipline.

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

These were learned the hard way — several contrast and ARIA regressions shipped during
this refactor and were only caught in review. Keep them true:

- **Never use `--pk-warning` as text color on the page background.** It's tuned as a fill
  color; as text on `--pk-bg` it measured 2.94:1, well under WCAG AA. `--pk-warning-text`
  exists for exactly this (sensitive-value styling, warning copy) and is contrast-tuned
  against `--pk-bg` in both themes.
- **A saturated fill (`--pk-success`, `--pk-warning`, `--pk-danger`, `--pk-primary`) needs
  its own on-colour token as foreground** — `--pk-on-success`, `--pk-on-warning`,
  `--pk-on-danger`, `--pk-on-primary` respectively. **Never `--pk-text-strong` or literal
  `white`/`#fff` on one of these fills** — both have shipped as regressions (`white`/
  `--pk-text-strong` measured 2.53:1 and, in another spot, ~2.3:1 in dark mode; the
  `--pk-on-*` tokens clear 4.5:1+ in both themes by construction).
- **Interactive elements are real controls with `:focus-visible`**, not `div`/`role`
  approximations. In particular: a `role="button"` container must not wrap a focusable
  child (e.g. a link) — ARIA defines a button's children as presentational, so assistive
  tech can prune the nested control right out of the accessibility tree, even though nothing
  looks wrong visually. The fix is always the same shape: the container stays a plain
  element, a real `<button>` carries the primary action, and the other interactive element
  becomes the button's **sibling**, not its descendant. This exact mistake was made and
  corrected twice in this refactor (the toolbar's open button, then the traces tab's trace
  item header) — watch for it in any future "make this row clickable" change.

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
| `#traces-bucket` and `data-bucket` | The All/Errors/Slow bucket filter buttons; tests select and click by `[data-bucket="..."]`. |
| `#<tabId>-tab` panel convention | Each dashboard tab's content lives in a `<section id="<id>-tab">`; `main.js`'s `renderTab()` looks it up by this exact id, and tests wait on `#<tabId>-tab.active`. |
| `data-tab` | The tab-strip button attribute (`.pk-tab[data-tab="<id>"]`) that `tabStrip()` and `main.js` both key off for selection state. |

## `application-test.yml`: the export delay

`peekaboot-testing-app/src/test/resources/application-test.yml` sets
`management.opentelemetry.tracing.export.schedule-delay: 50ms`. The OpenTelemetry SDK's
default `BatchSpanProcessor` delay is 5s; Playwright tests open the trace overlay
immediately after page load, well inside that window, so without this override the
overlay's spans/queries/logs tabs would assert against an empty trace that hadn't
reached the `TraceStore` yet.

## How to add a dashboard tab

1. Create `dashboard/tabs/<id>.js` exporting:
   - `export const id = '<id>';`
   - `export const label = 'Display Name';`
   - `export function render(container, data, context) { ... }` — `context` is
     `{client, locale, timeZone, navigate, features}`, built by `main.js`'s
     `currentContext()`.
   - optionally `export function isAvailable(data, features) { ... }` — gates whether the
     tab's strip button is shown at all (see `metrics.js`/`traces.js` for real examples
     gating on a feature flag).
   - optionally `export function applyFilter(payload) { ... }` — lets another tab jump
     here with a pre-selected filter via `context.navigate(id, detail, payload)` (see
     `traces.js`).
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

(Verified against `overview.js` — `id`/`label`/`render(container, data, {locale,
timeZone})` — and `traces.js`/`metrics.js`, which additionally export `isAvailable`;
`traces.js` also exports `applyFilter`.)
