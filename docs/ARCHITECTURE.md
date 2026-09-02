# Peekaboot Architecture

Technical documentation for contributors and maintainers.

> This file and its siblings in `docs/` are for people changing the code. Consumer
> documentation — quick start, configuration, security, the dashboard tour — lives at
> [peekaboot.org](https://peekaboot.org).

## Module Structure

Seven reactor modules; the reactor table in [`BUILD.md`](../BUILD.md) says what each one
contains and which are published. The sections below follow the same split: backend,
frontend, auto-configuration.

## Persisted state

Two stores opt into the filesystem behind one switch:

```
peekaboot.storage.enabled = <local run>                       # on for a local launch
peekaboot.storage.dir     = ${user.home}/.peekaboot/<app>      # both stores
```

Like `peekaboot.dev-toolbar`, the switch follows the launch context rather than
`peekaboot.enabled`'s resolved value: on for an IDE or `spring-boot:run` launch, off
everywhere else, and an explicit setting wins in either direction. Switching Peekaboot on
deliberately in a shared environment therefore writes nothing to that host's `$HOME`.

`<app>` is `<groupId>.<artifactId>` from `build-info.properties`, sanitized to
`[A-Za-z0-9._-]`; a build that publishes no build information falls back to
`spring.application.name`, and an application with neither to `application`. Coordinates
rather than the name so that two applications sharing a `spring.application.name` — or
having none at all — keep their history apart. An explicit `peekaboot.storage.dir` is
used verbatim, with no per-application subdirectory appended.

`StorageDirectory` only resolves this path — it never touches the disk itself; while
`peekaboot.storage.enabled` is `false`, `StorageDirectory.file(...)` returns empty and
neither store ever writes. Every write goes through `OwnerOnlyFiles.replaceAtomically`,
the single implementation of the mechanism: it creates the parent directory, writes to a
sibling `*.tmp`, moves that over the target with `ATOMIC_MOVE` (a plain replace where the
file system offers none), and deletes the temporary if the write fails. On a POSIX file
system the directory is created `rwx------` and every file `rw-------` regardless of the
process umask (an existing directory keeps its permissions), and the temporary is always
created fresh with `CREATE_NEW` after removing whatever sat at its path — a symlink planted
at `*.tmp` is deleted, never followed. What stays with the stores is failure handling: on
`IOException` each logs and continues in memory rather than taking the host application
down over an unwritable `$HOME`.

Both stores assume one application instance per directory. Two instances pointed at the
same `peekaboot.storage.dir` overwrite each other's files: the loser's history is lost,
and a half-written file is discarded on the next read like any other unusable one, so the
cost is lost history rather than corruption. Peekaboot is a development tool, and
coordinating instances is out of its scope — give each instance its own
`peekaboot.storage.dir` if you run several against one home directory.

### `insights.snapshot`

The insights ring buffers' contents: every level's geometry (`intervalMs`, `size`,
`endEpochMs`, `count`) and, per series, one ring per level — a single `values` column
at level 0, eight aggregate columns (`min`, `max`, `avg`, `median`, `p90`, `p95`, `p99`,
`samples`) at every coarser level. `samples` is carried alongside the seven the API
exposes because the next roll-up weights its average by it; omitting it would restore
rings whose first roll-up computes the wrong average. `InsightsSnapshotCodec` reads and
writes this as a small versioned binary format (magic `"PKIN"`, a schema version, then
the header and body described above); `InsightsSnapshotStore` owns the file, writing it
through `OwnerOnlyFiles.replaceAtomically` on each boundary of
`peekaboot.insights.persistence.interval` (default: the coarsest level's own interval)
and once more, synchronously, at shutdown, after the collector has stopped so
the final write sees quiesced rings. A run that never ticked skips the write rather than
overwriting a good file with an empty one.

The snapshot is a cache, never a source of truth: anything wrong with it — a bad magic
number, a schema version this build doesn't know, a ring geometry that no longer
matches `peekaboot.insights.levels`, or an age past
`peekaboot.insights.persistence.max-age` (default: the coarsest level's span) — costs
only the history. The file is deleted and the rings start empty, exactly as they would
with storage off. Every length read from the file is checked against a plausibility
bound before it is used to allocate, so a corrupt file can never provoke an oversized
allocation.

Loading never delays an application's startup. `InsightsSnapshotStore.beginLoad()`
submits the parse to a virtual thread and returns immediately; nothing on the startup
path awaits it. Instead, each of the collector's level threads runs a one-shot restore
just before its *first* write, gated by `SnapshotRestoreBarrier` so only the thread that
arrives first applies the snapshot — a level-1 roll-up can never land ahead of the
restore it depends on. That thread waits up to 5 seconds for the parse; a snapshot that
lands after the wait is discarded rather than layered on top of live samples. Restoring
a level's `endEpochMs` is what turns the outage into a visible gap on the chart: the
collector's `fillMissed()` — the same code that pads a suspended
laptop or a stalled sampler — runs at that level's next tick or roll-up and pads exactly
the missed interval, capped at the ring size.

### `lifecycle.jsonl`

The application's start/stop history: one JSON object per line, at most 1000 events
(oldest dropped first), read on a virtual thread rather than the startup path.
`LifecycleEventFile` rewrites the whole file through the same
`OwnerOnlyFiles.replaceAtomically` on every change — cheap at this size (≤400 KB for the
full 1000), and it removes both a partial-line corruption window and a second trim code
path. A line that fails to parse
is skipped on read; the rest of the file still loads. A start event carries every
`BuildProperties` and `GitProperties` entry the application has, plus its epoch
timestamp and pid; a stop event carries only its own timestamp and pid; its build
belongs to the start it follows, which the log still remembers.

The log's in-memory half runs independently of `peekaboot.storage.enabled`: with
storage off, `LifecycleEventLog` still records the current run's start and stop in
memory and serves them from there — `LifecycleEventFile` is simply never consulted. This
is why a dashboard with persistence switched off still shows one start marker for the
run in progress.

### API and dashboard

`GET /peekaboot/api/lifecycle/events`, gated by the `peekaboot.lifecycle.enabled`
property, serves the log as start/stop markers. For each start, `LifecycleEvents`
compares it against the previous start already in the log and includes `version`,
`branch`, `commitId`/`shortCommitId` and `buildTimeEpochMs` only when they differ from
it (all four are present on the log's first entry); a start whose predecessor in the
log is itself a start — no stop in between — is flagged `uncleanPrevious`. Computing
this diff server-side, rather than in the dashboard, gives the browser one definition of
"what changed" instead of reimplementing it wherever a marker is drawn. This endpoint
complements the insights endpoints under `/peekaboot/api/insights/*`
(`/config`, `/data`, `/stream`): the dashboard fetches lifecycle events alongside the
ring data and renders them as restart markers over the same charts.

`GET /peekaboot/api/lifecycle/runs`, gated by the same property, is the second
projection over the same log. A chart marker only wants to say what's new, so
`LifecycleEvents` nulls a field the moment it repeats the previous start; a table row has
no previous cell to inherit from, so `LifecycleRuns` makes every run stand on its own —
`version`, `branch`, `shortCommitId` and `buildTimeEpochMs` are carried forward from the
last start that actually reported them, and `changed` is the separate, explicit answer to
whether this run was a deployment: which of `version`, `branch` and `commit` differ from
the run before it. The oldest run in the log has no predecessor to differ from, so it is
never a deployment.

A run's `stoppedAtEpochMs` and `ranForMs` are null, not a guess, when its start has no
matching stop: a `kill -9` writes nothing, so when that run actually ended is genuinely
unknown. `downForMs`, the gap since the previous run stopped, is null for the same
reason whenever the previous run itself has no recorded stop — there is nothing to
measure the gap from, and null there means unknowable, not zero. Because the log caps at
1000 events, a long-lived application's retained history begins mid-cycle rather than at
its first start, and the oldest surviving event is often a stop whose own start already
aged out; that leading, orphaned stop is normal, and — since it still carries a real
timestamp — the downtime between it and the next start is still knowable and reported.

The dashboard's Lifecycle tab renders `/runs` as this history's second view, a table
alongside `/events`' markers on the charts, 20 rows to a page. Paging is done client-side
over the single fetch: the log's 1000-event cap bounds the response at roughly 500 runs,
small enough to hold in the browser and page through locally rather than adding
pagination parameters to the endpoint.

### Shutdown banner

`ApplicationStoppedListener`, mirroring the ready banner's frame, logs a banner on
`ContextClosedEvent` reporting how long the application ran. Its uptime is measured from
`ApplicationContext.getStartupDate()` — the context's own refresh — rather than the
`ApplicationReadyEvent` timestamp the lifecycle log and chart markers use: it stays
available even when the context closes before the application ever became ready, at the
cost of a few seconds' divergence from "ready" that the banner's own label names.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Application                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   ┌──────────────────┐    ┌──────────────────┐                      │
│   │  DevToolbarFilter │    │RequestCaptureFilter│                   │
│   │  (HTML injection) │    │  (request metadata)│                   │
│   └────────┬─────────┘    └────────┬───────────┘                    │
│            │                       │                                 │
│            │    ┌──────────────────┴───────────┐                    │
│            │    │     Micrometer Tracer        │                    │
│            │    │  (tracer.currentSpan())      │                    │
│            │    └──────────────────────────────┘                    │
│            │                                                        │
│            └───────────┬───────────────────────────────────────────│
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │      ApplicationEventPublisher           │                      │
│   │    (Spring's standard event system)      │                      │
│   └────────────────────┬─────────────────────┘                      │
│                        │                                            │
│    ┌───────────────────┼───────────────────┐                       │
│    ▼                   ▼                   ▼                       │
│ SpanDataEvent   LogCapturedEvent   RequestCompletedEvent           │
│                        │                                            │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │        TraceStoreEventListener           │ ◄── @EventListener   │
│   │      (forwards events to the store)      │                      │
│   └────────────────────┬─────────────────────┘                      │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │    TraceStore (InMemoryTraceStore)       │ ◄── three bounded,   │
│   │   All / Errors / Slow buckets            │     insertion-ordered│
│   │                                          │     maps             │
│   └────────────────────┬─────────────────────┘                      │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │          PeekabootController             │                      │
│   │   /peekaboot/api/* endpoints             │                      │
│   └──────────────────────────────────────────┘                      │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## peekaboot-backend

Core module containing all business logic.

### Package Structure

```
org.peekaboot.backend/
├── actuator/parsed/        # Typed beans for actuator responses (ActuatorResponseParser)
├── config/                 # PeekabootProperties, UiTracingProperties, PeekabootWebConfig (+ ApiSecurityHeadersInterceptor: no-store/nosniff on /peekaboot/api/**), PeekabootJson (+ its message converter), PeekabootPaths
├── controller/             # PeekabootController — /peekaboot/api/* (actuator data, metrics, traces, features)
├── devtoolbar/             # ToolbarShell (server-rendered markup), ToolbarDataProvider
├── domain/                 # Domain models, one sub-package per dashboard concern
│   ├── application/, config/, datasource/, environment/, flyway/, health/,
│   ├── insights/ (incl. ActuatorInsightsResponse, the dashboard's aggregate DTO), lifecycle/, loggers/, metrics/, runtime/, scheduledtasks/, server/
│   ├── features/           # Features — the /api/features payload: flags plus the effective slow thresholds
│   └── trace/              # TraceTree, SpanNode, HttpExchange, TraceTabSummary, IssueType, SpanStatus, IssueSeverity, ...
├── filter/                 # DevToolbarFilter, RequestCaptureFilter, ContentBufferingResponseWrapper
├── insights/               # Metric ring buffers: InsightsCollector, StatsRing, snapshot codec/store, IntervalBoundary (the boundary-aligned schedule the level threads and the snapshot writer share)
│   ├── config/             # InsightsProperties, panels file (PanelDef, SeriesDef, TileDef)
│   └── web/                # InsightsController, InsightsSsePublisher — /peekaboot/api/insights/*
├── lifecycle/              # Ready/stopped banners, LifecycleEventLog + LifecycleEventFile, build info, HikariPoolInfo (the one Hikari reference, wired only with HikariCP present), ByteFormat (the one byte formatter; insights uses it too)
│   └── web/                # LifecycleController — /peekaboot/api/lifecycle/*
├── log/                    # PeekabootLogbackAppender
├── mapper/                 # Data transformation
│   ├── actuator/           # Actuator → domain mappers, CronDescriber (cron expressions in words, for ScheduledTasksMapper)
│   └── trace/              # TraceTreeMapper, IssueDetector, QueryExtractor, DbSpans (the one "is this a query span" predicate)
├── masking/                # MaskingEngine (one bean, declared by PeekabootAutoConfiguration), MaskingRules, TagMasker, TreeMasker,
│                           # ConnectionParamsMasker — the one place "is this key/value sensitive" is decided; see peekaboot.org/docs/security
├── service/                # ActuatorInsightsService, TraceInsightsService, PeekabootActuatorService, ...
├── storage/                # StorageDirectory — resolves peekaboot.storage.dir; OwnerOnlyFiles — owner-only, symlink-safe writes (see Persisted state)
├── tracing/                # In-memory tracing
│   ├── bridge/otel/        # OtelSpanExporter
│   ├── config/             # PeekabootTracingProperties
│   ├── event/              # SpanDataEvent, LogCapturedEvent, RequestCompletedEvent
│   ├── interceptor/        # TracingHandlerInterceptor
│   └── store/              # TraceStore, InMemoryTraceStore, TraceDataBundle, SpanDuplicateMatcher,
│                           # TraceBucket, TraceStoreEventListener
```

### Tracing Flow

1. **Span Capture**: `OtelSpanExporter` receives spans from OpenTelemetry SDK
2. **Event Publishing**: Publishes `SpanDataEvent` via Spring's `ApplicationEventPublisher`
3. **Storage**: `TraceStoreEventListener` listens via `@EventListener` and forwards to `TraceStore` (`InMemoryTraceStore`), which keeps three insertion-ordered bounded maps: the All, Errors and Slow buckets
4. **Log Correlation**: `PeekabootLogbackAppender` reads `traceId`/`spanId` from the event's frozen MDC map (Logback events carry MDC state, not a live span), and drops events without a `traceId`
5. **Request Metadata**: `RequestCaptureFilter` uses `Tracer.currentSpan()` to correlate request details
6. **Query**: `TraceInsightsService` queries `TraceStore` directly by `TraceBucket` (ALL/ERRORS/SLOW)
7. **Thresholds**: `IssueDetector` raises SLOW/VERY_SLOW/SLOW_QUERY at `UiTracingProperties`' thresholds and sets each
   `TraceTree.slow` (some span carries SLOW or VERY_SLOW — the Traces tab's badge); `GET /peekaboot/api/features`
   publishes those thresholds plus the Slow bucket's `slowTraceThresholdMs` (`Features`), so the frontend colours by the
   same numbers instead of keeping a copy

### Servlet Filters

| Filter | Purpose |
|--------|---------|
| `DevToolbarFilter` | Renders the toolbar (markup, inlined styles, data) into HTML responses via `ToolbarShell`, and loads the script that enhances it |
| `RequestCaptureFilter` | Captures request/response metadata for traces |

Both use `PeekabootPaths` to skip static resources and peekaboot's own endpoints. That
class is the one place Peekaboot's URL space is defined: the `/peekaboot` prefix, the
excluded prefixes, and the same exclusions as MVC patterns for the tracing interceptor.
Everything in it is relative to the servlet context: the filters match on the container's
mapped path (`getServletPath() + getPathInfo()`, decoded and normalised) rather than the
raw request URI, so a `server.servlet.context-path` or a `/x/../peekaboot/...` spelling
cannot hide the dashboard's own calls from the exclusion, and every URL the toolbar
writes into a page — script, stylesheets, dashboard links, the `basePath` in its data blob
— is prefixed with `request.getContextPath()`. On the browser side `shared/api.js`
derives the same base path from its own module URL, so the dashboard and the overlay it
opens need no configuration either. The `/actuator/` exclusion follows
`management.endpoints.web.base-path`: `PeekabootPathsAutoConfiguration` constructs the
single `PeekabootPaths` bean with the resolved base path and threads it into both
filters, the interceptor registration and `OtelSpanExporter`'s span skip (a base path of
`/`, management endpoints at the application root, excludes nothing extra - there is no
prefix to tell them apart by). The bean also carries the resolved
`server.servlet.context-path`: a span's HTTP path tag is the raw request path with the
context path still in front, so the exporter matches it through
`isExcludedRequestPath`, which strips that prefix first — the host's actuator spans and
Peekaboot's own are skipped the same with and without a context path. Both filters
are also registered only inside `DevToolbarAutoConfiguration`, conditional on
`peekaboot.dev-toolbar` resolving to `true` — neither runs while it's off. Without the
toolbar on, a trace still carries a basic method/path/status summary (`summary.request`)
read off the root span's own HTTP tags by `HttpSpanTags`, which knows all three naming
schemes that reach the store: Spring Boot's default server-request observation (`method`,
`status`, `uri` — the route pattern — and `http.url`, which is the request URI and therefore
the path shown), the current OpenTelemetry names (`http.request.method`, `url.path`,
`http.response.status_code`) and their superseded spelling (`http.method`, `http.target`,
`http.status_code`). The overlay reads `summary.request` rather than the tags itself, so the
browser never has to know those names. Headers, query/form parameters
and the resolved controller class/method are never available without the toolbar;
correlated logs are likewise unavailable (see *Log Capture* below). Request/response body
content and uploaded file names have fields reserved for them on `HttpExchange` but aren't
populated by `RequestCaptureFilter` — not captured, regardless of dev-toolbar (see
[`IMPROVEMENTS.md`](IMPROVEMENTS.md) §1.1).

### Server-Timing Header

`RequestCaptureFilter`, dev-toolbar-only per above, sets a `Server-Timing` response header
carrying the current trace context in W3C `traceparent` form, before invoking the filter
chain:

```
Server-Timing: trace;desc="00-<traceId>-<spanId>-<traceFlags>"
```

Setting it before the chain runs ensures the header is present even when downstream
handling commits the response early. The toolbar's own idle-mode script — used on pages
like Swagger UI that have no request of their own to report — reads this header off
`fetch()` responses to pick up a trace id for a call it didn't otherwise see; nothing stops
any other tool that can read response headers from doing the same.

### BFF Pattern

The backend implements a Backend-for-Frontend pattern:

1. **Raw Actuator Data**: `PeekabootActuatorService` invokes actuator endpoints in-process (see below)
2. **Typed Parsing**: `ActuatorResponseParser.parse(...)` converts raw JSON to typed beans
3. **Domain Mapping**: Individual mappers transform to domain models
4. **Aggregation**: `ActuatorInsightsService` combines all data for the dashboard

```
Actuator Endpoints → Raw Beans → Domain Models → API Response
     (JSON)         (typed)      (clean DTOs)    (dashboard)
```

`ConfigMapper` flattens a nested `@ConfigurationProperties` value to one property per leaf
under a dotted key (`hikari.maximumPoolSize`), indexing list elements the way Spring's own
property syntax does (`servers[0].host`), so the Config tab's filter matches nested keys
and values. Masking runs on the tree first (`TreeMasker`, by leaf key), so a sensitive key
anywhere in it arrives as the single masked leaf its subtree collapsed to.

### JSON on the wire

Peekaboot's REST responses and its insights SSE events are serialised by
`PeekabootJson.MAPPER`, a plain default Jackson mapper, never by the application's own
Jackson bean. The dashboard reads camelCase names, tests some fields with `!== null`
(`loggers.js`), and parses every `Instant` as an ISO-8601 string; an application that
sets `spring.jackson.property-naming-strategy`, `default-property-inclusion=non_null` or
`datatype.datetime.write-dates-as-timestamps` must not silently reshape that.
`PeekabootJsonMessageConverter` (registered first by `PeekabootWebConfig`) claims every
return value whose class lives under `org.peekaboot.backend.` — the scope has to be the
value's type because Spring MVC picks converters per return value, not per controller —
and `InsightsAutoConfiguration` hands the same mapper to `InsightsSsePublisher`. The
default mapper writes byte for byte what an unconfigured Boot application writes, so a
host with default Jackson settings sees the same bytes either way
(`PeekabootJsonMessageConverterTest.writesCamelCaseNullsAndIsoInstants` pins the wire
shape). Every Peekaboot response goes through it: `PeekabootController.getFeatures()`
returns the `Features` record and `InsightsController`'s 400 body is a record in
`org.peekaboot.backend.insights.web`, both under the converter's package prefix.

### In-Process Actuator Invocation

Peekaboot never calls `/actuator/*` over HTTP. `PeekabootActuatorService` builds
its own `WebEndpointDiscoverer` with empty endpoint filters — bypassing
`management.endpoints.web.exposure` *filtering* — and invokes each endpoint's
READ operation directly (`operation.invoke(...)`). Data therefore flows without
any actuator endpoint being reachable over the web.

Health is the one exception to the discoverer path. The web operation the discoverer
finds under `health` is `HealthEndpointWebExtension`, which applies the application's
`management.endpoint.health.show-details` — a setting that belongs to the application's
own public `/actuator/health` and that Peekaboot must not widen. The service therefore
reads the `HealthEndpoint` bean itself: `HealthEndpoint.health()` always carries the
components and their details, so the dashboard has full health while `/actuator/health`
keeps answering anonymous callers with the aggregate status only.
`ActuatorResponseParser` accordingly parses the bare `HealthDescriptor` shape (`status`,
`components`, `groups`), not a `WebEndpointResponse` wrapper. A composite contributor —
Spring's `db` as soon as there are two DataSources, or any custom composite — nests its
children under a further `components` map: `HealthMapper` flattens them to `db/<name>`
for the dashboard's single list, and `DataSourceMapper` reads each DataSource's own
child status rather than the composite's aggregate.

Bypassing exposure filtering is not enough on its own: Spring Boot only
*creates* an endpoint bean when `@ConditionalOnAvailableEndpoint` matches, i.e.
the endpoint is accessible **and** exposed via web, JMX (only when
`spring.jmx.enabled=true`), or a custom contributor. Spring Boot's default web
exposure is `health` only, so without help the `env`, `configprops`, `loggers`,
`flyway`, and `scheduledtasks` beans would never exist.

`PeekabootEndpointExposureOutcomeContributor` (registered under
`EndpointExposureOutcomeContributor` in `META-INF/spring.factories`, a Spring
Boot 3.4+ extension point) closes that gap: while `peekaboot.enabled=true` it
reports every web-capable endpoint as exposed, so the beans are created. The
regular HTTP mapping under `/actuator` still applies the
`management.endpoints.web.exposure` property, so this does **not** expose any
endpoint over the web — with Spring defaults only `/actuator/health` is
reachable via HTTP while the dashboard has full data.

Whether the `env` and `configprops` beans' own values are visible to this in-process
invocation, rather than masked, is a separate question from whether the beans exist at
all: see *Default Properties* below — value visibility comes from the
`peekabootDetection` property source and applies only on a local run, not from a blanket
default in `peekaboot-defaults.yml`.

See [peekaboot.org/docs/security](https://peekaboot.org/docs/security/) for what this
exposure model means in practice for securing a deployment.

## peekaboot-frontend

Static resources served from `/peekaboot/ui/`, backing three UI surfaces — the
standalone dashboard, the dev toolbar injected into host-app pages, and the trace-detail
overlay — that share one design system. See `peekaboot-frontend/README.md` for the full
picture (the shared-layer split, the shadow-DOM delivery mechanism, theme resolution,
accessibility invariants, and the ids the test suite depends on); this section covers
only the headline decisions. The file inventory — every module under
`META-INF/peekaboot/ui/` and what it exports — lives in that README alone.

### Design Principles

- **No build step**: Plain HTML/CSS/JS, ES modules
- **Shared design system, three surfaces**: `assets/tokens.css`/`base.css`/`components.css`
  are consumed by the dashboard document directly and by the toolbar's and overlay's
  shadow roots via `attachSharedStyles()` — a "doubled selector" (`:root, :host { ... }`)
  lets the identical stylesheet apply in both contexts, so no surface carries its own
  palette, `escapeHtml`, duration thresholds or collapsible-group CSS.
- **Shadow DOM**: Toolbar and trace-detail overlay isolated from host app styles
- **Responsive dashboard, desktop-first toolbar and overlay**: the dashboard is responsive;
  the toolbar wraps and the overlay's gantt reflows below 768px, but both assume a desktop
  viewport for their full layout
- **Lazy loading**: Trace-detail overlay JS loaded only on first use (dynamic `import()`
  from `toolbar.js`; eager static import from `dashboard/main.js`)
- **Theme support**: `--pk-*` CSS custom properties for dark/light modes, resolved once
  in `shared/theme.js` from `localStorage['peekaboot-theme']` (falling back to
  `prefers-color-scheme`) and shared across all three same-origin surfaces

See [peekaboot.org/docs/theming](https://peekaboot.org/docs/theming/) for how a consuming
application overrides `tokens.css` to re-theme all three surfaces.

## peekaboot-spring-boot-autoconfigure

Auto-configuration classes that wire everything together. The nine `@AutoConfiguration`
classes are registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
the three hooks that run before or outside the application context are registered in
`META-INF/spring.factories`.

| Class | Registered via | Purpose |
|-------|----------------|---------|
| `PeekabootAutoConfiguration` | `.imports` | Core beans (controller, services, mappers, web config) as explicit `@Bean` methods, each `@ConditionalOnMissingBean` |
| `DevToolbarAutoConfiguration` | `.imports` | Toolbar filter registration, `LogbackAppenderRegistrar` |
| `PeekabootLifecycleAutoConfiguration` | `.imports` | Ready/stopped listeners, lifecycle event log and its API |
| `PeekabootStorageAutoConfiguration` | `.imports` | `StorageDirectory` — where the insights snapshot and lifecycle log are kept; no web/actuator conditions |
| `InsightsAutoConfiguration` | `.imports` | Metrics collector/service, SSE fan-out and the insights controller; requires a `MeterRegistry` bean |
| `PeekabootTracingAutoConfiguration` | `.imports` | Tracing properties and store |
| `OtelTracingAutoConfiguration` | `.imports` | OpenTelemetry span exporter |
| `TracingInterceptorAutoConfiguration` | `.imports` | Tracing handler interceptor |
| `PeekabootPathsAutoConfiguration` | `.imports` | The single `PeekabootPaths` bean (see *Servlet Filters*) |
| `PeekabootDefaultsEnvironmentPostProcessor` | `spring.factories` (`EnvironmentPostProcessor`) | Local-dev detection for `peekaboot.enabled`, `peekaboot.dev-toolbar` and `peekaboot.storage.enabled` (plus, on a local servlet run, the `env`/`configprops` `show-values` keys) + default property values |
| `PeekabootEndpointExposureOutcomeContributor` | `spring.factories` (`EndpointExposureOutcomeContributor`) | Makes actuator endpoint beans available without web/JMX exposure |
| `LogbackCaptureReinstaller` | `spring.factories` (`ApplicationListener`) | Re-attaches the log-capture appender after Spring Boot's `LoggingApplicationListener` re-initialises Logback |
| `LocalDevDetector` | — (package-private helper) | The local-launch heuristic behind the post-processor (see *Conditional Loading*) |

Every `@Bean` method across these auto-configurations is `@ConditionalOnMissingBean` —
matched by name for the anonymous `WebMvcConfigurer` registration, by the deduced
generic type for the `FilterRegistrationBean`s — so an application bean of the same type
or name replaces any Peekaboot default instead of colliding with it.

### Conditional Loading

Auto-configuration uses Spring Boot conditionals. Seven of the nine classes &mdash;
`PeekabootAutoConfiguration`, `PeekabootPathsAutoConfiguration`,
`DevToolbarAutoConfiguration`, `TracingInterceptorAutoConfiguration`,
`PeekabootTracingAutoConfiguration`, `OtelTracingAutoConfiguration` and
`InsightsAutoConfiguration` &mdash; carry the same two class-level conditions, the servlet
guard and the master switch:

```java
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
```

Each adds its own on top: `PeekabootAutoConfiguration` needs the `HealthEndpoint` and
`InfoEndpoint` classes, `DevToolbarAutoConfiguration` additionally requires
`peekaboot.dev-toolbar`, `TracingInterceptorAutoConfiguration` an `ObservationRegistry`
bean, `InsightsAutoConfiguration` a `MeterRegistry` bean and `peekaboot.insights.enabled`,
`OtelTracingAutoConfiguration` the OpenTelemetry SDK's `SpanExporter` class.

`PeekabootAutoConfiguration` &mdash; the class that registers the servlet-only
`PeekabootWebConfig` next to the controllers, services and actuator wiring, all as
explicit `@Bean` methods, each `@ConditionalOnMissingBean` so an application bean of the
same type or name replaces the default instead of colliding with it (the method names
yield the class-derived bean names `ServerUrlResolver`'s dashboard check relies on)
&mdash; needs it because
`PeekabootWebConfig implements WebMvcConfigurer`, a servlet-only type;
without the guard, a non-servlet application (WebFlux, or no web application at all) with
`peekaboot.enabled=true` would still register the controller, actuator services and
mappers, just with nothing servlet-specific ever invoking them &mdash; dead beans and
wasted registration work, not a startup crash (`ApplicationContextRunner` confirms the
context starts cleanly either way; only `WebMvcConfigurationSupport`, itself only wired up
in a real servlet context, ever calls back into `WebMvcConfigurer`). The guard prevents
even that. `PeekabootTracingAutoConfiguration` and `OtelTracingAutoConfiguration` carry it
for the same reason: everything that reads the trace store is servlet-only, so a WebFlux or
non-web application would otherwise fill an `InMemoryTraceStore` for nobody.
`PeekabootLifecycleAutoConfiguration` and `PeekabootStorageAutoConfiguration` carry no
class-level servlet guard &mdash; the ready/stopped summaries, the run history and the
storage directory must work in a plain non-web application &mdash; and only the lifecycle
API's `LifecycleController` bean is servlet-gated on its own. `PeekabootDefaultsEnvironmentPostProcessor`
applies the same split to the property defaults: the activation, storage and value-visibility
detection is web-type independent, while `peekaboot-defaults.yml` and the dev-toolbar defaults
are skipped for a non-servlet application (see *Default Properties*).

`PeekabootTracingAutoConfiguration`, `OtelTracingAutoConfiguration` and
`TracingInterceptorAutoConfiguration` additionally require `peekaboot.tracing.enabled`
(default on): handler and view observations are tracing, and with it off there is no store
for them to land in. `DevToolbarAutoConfiguration` does not read that property at all;
instead its capture half — the `RequestCaptureFilter` registration and the Logback
appender registrar — is `@ConditionalOnBean(TraceStore.class)`. With
`peekaboot.tracing.enabled=false` the toolbar is therefore still injected into every page,
and nothing is captured behind it.

There is no `matchIfMissing` fallback for `peekaboot.enabled` or
`peekaboot.dev-toolbar` — both default from `PeekabootDefaultsEnvironmentPostProcessor`,
which adds them (and, on a local run, actuator value visibility — see *Default
Properties*) to a `peekabootDetection` property source at lowest precedence, so any
explicit application setting always wins, in either direction. The toolbar deliberately
keys on the same local-development detection as `peekaboot.enabled`, not on
`peekaboot.enabled`'s own resolved value: an application that turns Peekaboot on
explicitly in a shared environment does not get the toolbar injected into every page, or
its own `/actuator/env` widened, as a side effect.

`LocalDevDetector` starts from the heuristics Spring Boot DevTools itself uses and adds
two signals of its own, checked in order:

1. Running as a native image always resolves to `false`, before anything else is checked.
2. Otherwise, if the current thread's context class loader is DevTools' `RestartClassLoader`
   (the `restartedMain` thread DevTools relaunches the app on), only the container check
   below is left to decide: DevTools relaunches like that for a local launch, so the class
   loader stands in for the class-path proof — but a Jib image and Boot's `extract` layout
   ship DevTools whenever the application has it as a runtime dependency, so a container
   marker still resolves `false`.
3. Otherwise, the result is `true` only when *all* of the following hold: the thread is
   named `main`; its context class loader is the JDK's own `AppClassLoader` — not Spring
   Boot's `LaunchedClassLoader` (a packaged, executable jar) and not a servlet container's
   webapp loader (a deployed war); and the call stack carries no `org.junit.runners.`,
   `org.junit.platform.`, `org.springframework.boot.test.`, Spring Boot's AOT processor, or
   `cucumber.runtime.` frames. This is also why a `@SpringBootTest` run resolves `false`
   despite sharing the same thread name and class loader as a genuine local launch — the
   stack-trace check tells the two apart.
4. Those three hold for *every* exploded-classpath launch, so two more signals decide
   (`LocalDevDetector.LaunchSignals`, read from the JVM and the host, injectable in tests):
   `java.class.path` must contain a build tool's output directory — an entry ending in
   `target/classes`, `build/classes/java/main`, `build/classes/kotlin/main`,
   `build/classes/groovy/main`, `build/classes/scala/main` or `bin/main`, or containing
   `out/production/` — which an IDE, `spring-boot:run` and `bootRun` always put
   there and a Jib image (`/app/classes`) or Boot's `extract` layout (a thin jar with a
   `Class-Path` manifest) never do; and the backend's shared `ContainerRuntime.current()`
   (also behind the Overview tab's Machine card) must report `NONE` — no `/.dockerenv`, no
   Podman `/run/.containerenv`, no `KUBERNETES_SERVICE_HOST` variable, and no
   `/proc/1/cgroup` naming `docker`, `kubepods` or `containerd`. A build-output classpath
   inside a container still resolves `false`.

In practice: an IDE run, `mvn spring-boot:run`, and `gradle bootRun` on a developer's machine
default to on. A `java -jar` of the packaged artifact (Boot's `LaunchedClassLoader`), a war in
a servlet container, a native image, an AOT-processed build, a test, a Jib image, the
`extract` layout, a plain `java -cp` of jars, and anything running inside a container all
default to off. What is left for an explicit `peekaboot.enabled=false` is the one shape the
signals cannot separate: a build output directory mounted into a non-container process that
is not a developer's own launch.

`peekaboot.lifecycle.enabled` (`PeekabootLifecycleAutoConfiguration`'s own switch) is read
by a `@ConditionalOnBooleanProperty` condition, since it has to be evaluated before any
Peekaboot bean exists, and is also bound as `PeekabootProperties.Lifecycle.enabled` so it
carries configuration metadata and shows up on the dashboard's own Config tab like every
other switch.

### Default Properties

`PeekabootDefaultsEnvironmentPostProcessor` loads three yml resources with lowest
precedence, all overridable by an app's own `application.yml`:

- `peekaboot-defaults.yml` &mdash; enables full observability, but only when Peekaboot is
  enabled *and* the application is a servlet web application; skipped entirely otherwise,
  since everything that would read it &mdash; the dashboard, the filters, the trace store
  &mdash; is servlet-only. The web type is `spring.main.web-application-type` where the
  environment carries it, and otherwise the one `SpringApplication.getWebApplicationType()`
  deduced: Boot binds `spring.main.*` onto the application only after the post-processors
  have run. The dev-toolbar defaults below sit behind the same check.
- `peekaboot-no-push-defaults.yml` &mdash; applies unconditionally, even when Peekaboot
  itself is disabled, to keep telemetry from leaving the process by default.
- `peekaboot-dev-toolbar-defaults.yml` &mdash; applied only when the dev toolbar resolves
  on. Sets `management.opentelemetry.tracing.export.schedule-delay` to `200ms` (Spring
  default `5s`), trading export throughput for latency so a trace is readable in the
  toolbar while the developer is still looking at the page.

`management.endpoint.env.show-values` and the `configprops` equivalent are deliberately
not in `peekaboot-defaults.yml`: they're set by the `peekabootDetection` property source
instead (the same one that resolves `peekaboot.enabled` and `peekaboot.dev-toolbar` — see
*Conditional Loading*), and only on a local servlet run whose `peekaboot.enabled` also
resolves true, never as an explicit `never` off-local. An application that switches
Peekaboot on in a shared environment therefore doesn't have its own `/actuator/env`
widened as a side effect:
off-local, Spring's own default (`never`) applies and every property masks, exactly as it
would without Peekaboot at all.

How those sources are contributed matters. Boot moves `defaultProperties` — the source
`SpringApplication.setDefaultProperties` fills — to the end of the environment once every
post-processor has run, so a source merely appended last would outrank it. Where that
source exists, Peekaboot folds its entries into it, underneath the application's own, so
`SpringApplicationBuilder.properties("peekaboot.enabled=false")` wins like any other
setting. Where it does not, Peekaboot's four named sources — `peekabootDetection`,
`peekabootNoPushDefaults`, `peekabootDefaults` and `peekabootDevToolbarDefaults` — are
appended last, in that order. `PeekabootDefaultsRegistrationTest` pins both halves.

## Tracing Integration

### OpenTelemetry Bridge

When OpenTelemetry is on the classpath, `OtelSpanExporter` is registered
(`OtelTracingAutoConfiguration`; the class condition is on the auto-configuration, not
the bean method):

```java
@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
// ... the servlet guard, peekaboot.enabled and peekaboot.tracing.enabled
public class OtelTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OtelSpanExporter otelSpanExporter(ApplicationEventPublisher eventPublisher, PeekabootPaths peekabootPaths) {
        return new OtelSpanExporter(eventPublisher, peekabootPaths);
    }
}
```

The exporter:
- Is one more `SpanExporter` bean alongside whatever Spring Boot's own OpenTelemetry
  auto-configuration already registered — it doesn't stand up its own tracing stack, it
  just copies every span it sees into `TraceStore` as well. Turning
  `peekaboot.tracing.enabled` off leaves the rest of the app's OpenTelemetry setup
  (sampling, other exporters — Zipkin, Jaeger, an OTLP backend) untouched.
- Receives finished spans from OTel SDK
- Filters out peekaboot's own requests (`/peekaboot/**` and the management base path -
  `/actuator/**` by default), span by span. A child of such a request carries neither the
  path tag nor the route name, so skipping a *root* also publishes a `TraceDiscardedEvent`;
  `TraceStoreEventListener` turns that into `TraceStore.discard`, which drops the trace from
  all three buckets
- Converts to `SpanData` and publishes `SpanDataEvent` via Spring's
  `ApplicationEventPublisher`; `TraceStoreEventListener` receives it via an
  `@EventListener` and forwards it to `TraceStore`
- Records an error only for a span whose status code is `ERROR`: the message is the status
  description, falling back to the `exception` event's `exception.message` when the
  description is empty, and the class is that event's `exception.type` — `ERROR` where the
  span recorded no exception event

### Micrometer Tracer Integration

On the request path — `RequestCaptureFilter` and `DevToolbarFilter` — Peekaboot reads the
trace context from Micrometer's `Tracer` API:

```java
// Get current trace context
Span currentSpan = tracer.currentSpan();
if (currentSpan != null) {
    String traceId = currentSpan.context().traceId();
    String spanId = currentSpan.context().spanId();
}
```

That keeps the filters compatible with Spring Boot's tracing auto-configuration whether or
not MDC propagation is enabled. Log capture is the exception: `PeekabootLogbackAppender`
runs inside Logback, where the only trace context available is the event's frozen MDC map,
so it reads `traceId`/`spanId` from there and depends on Micrometer's MDC propagation
(Spring Boot's default) being on.

### Log Capture

`PeekabootLogbackAppender` captures log events with trace correlation:

1. Registered via `LogbackAppenderRegistrar` bean in `DevToolbarAutoConfiguration`
2. Publishes `LogCapturedEvent` via Spring's `ApplicationEventPublisher`
3. `TraceStoreEventListener` receives events via `@EventListener` and forwards them to `TraceStore`, which stores by traceId
4. Logs are associated with spans and included in trace detail views

Because `LogbackAppenderRegistrar` is registered only inside `DevToolbarAutoConfiguration`,
correlated logs require `peekaboot.dev-toolbar=true` — a trace's Logs tab stays empty
without it, independent of `peekaboot.tracing.enabled`.

### Span Deduplication

Deduplication runs primarily **on write**, in `TraceDataBundle.addSpan`: as each span
arrives at the trace store, `SpanDuplicateMatcher.isDuplicate` collapses a child span into
its parent when they share a name and their tags match once `peer.service` and
`jdbc.datasource.name` are ignored — the shape produced when a single operation is
instrumented by more than one layer, e.g. a JDBC driver-level span and a
`datasource-proxy` span for the same query. The removed span's own children are
re-parented onto the nearest surviving ancestor so the tree stays connected.

`peekaboot.tracing.max-spans-per-trace` (default **500**) then caps the
already-deduplicated span count, so the cap counts real, distinct work rather than a
double-instrumented call as two spans against it. Once a trace's deduplicated span count
still exceeds the cap, its **oldest** spans are dropped to make room for new ones, and the
trace is flagged `truncated: true` — exposed on both `GET /peekaboot/api/traces/insights`
and `GET /peekaboot/api/traces/{traceId}/insights`, and shown as a `TRUNCATED` badge in the
trace list and the trace-detail overlay.

### Query Extraction

Database queries aren't captured specially: a query shows up in a trace because the
JDBC/datasource instrumentation on the classpath already emits a span for it, tagged with
`db.*` or `jdbc.query*` attributes. **`DbSpans.isQuery` is the one definition of a query
span** — the CLIENT side of a database call carrying a `db.*` or `jdbc.query*` tag
(`jdbc.*` alone is not enough: datasource-proxy's connection and result-set spans carry
`jdbc.datasource.name`/`jdbc.row-count` and are not queries) — shared by
`TraceTreeMapper` (`summary.queries.count`), `IssueDetector` (SLOW_QUERY and the
HIGH_QUERY_COUNT children count) and `QueryExtractor` (the `queries` list), so the three
numbers a trace reports about its queries are one number; `TraceTreeMapperTest` pins the
equality.

`QueryExtractor` builds each trace's `queries` list from those spans, independently of the
span tree's own names, one entry per query span — a span whose instrumentation recorded no
statement is listed with `sql: null`. `DbSpans.sql` checks tags in priority order:
`db.query.text` (the current OpenTelemetry semantic convention, emitted by
`datasource-micrometer-opentelemetry` — the default stack `peekaboot-testing-app` itself
uses) ahead of `db.statement` (that convention's superseded spelling, so when a library
emits both, the current one is authoritative); then `jdbc.query[N]`
(datasource-proxy/Micrometer); then, only if nothing tagged the span, the span's own name
if it looks like SQL. The same masked text is put on the span itself as `SpanNode.query`,
which is what the Spans tab's SQL toggle shows. `findDbSystem` mirrors this priority for
`db.system.name` / `db.system` / `jdbc.datasource.name` / `peer.service`. Matching is
value-patterns only, not column-aware literal masking — see the class Javadoc for why.

**Two separate pipelines render a query, and only one of them depends on
`QueryExtractor`.** The Spans tab (`trace-detail/tabs/spans.js`) renders `span.name`
directly — OpenTelemetry's own span-name summary, e.g. `SELECT customer_order` — which is
correct and expected for a span tree. The Queries tab (`trace-detail/tabs/queries.js`)
renders `query.sql`, which is what `QueryExtractor` populates; this is where the tag
`DbSpans.sql` picks actually shows up. The overlay opens on Spans by default
(`trace-detail.js`'s `initial: 'spans'`); `ScreenshotCapture` photographs both views, so
`trace-detail-queries-*` is the shipped image that demonstrates `QueryExtractor`'s output
(see [`IMPROVEMENTS.md`](IMPROVEMENTS.md) §5.7).

## Data Models

### Trace Domain

```
TraceData
├── traceId: String
├── startTime, endTime, duration
├── spanCount: int
└── spans: List<SpanData>

SpanData
├── traceId, spanId, parentId
├── name, kind
├── startTime, endTime, duration
├── tags: Map<String, String>
├── events: List<Event>
├── errorMessage, errorClass
├── remoteServiceName
└── creationOrder: long
```

### Insights Domain

```
ActuatorInsightsResponse
├── application: ApplicationInfo
├── runtime: RuntimeInfo (os, memory, storage, process, machine)
├── dataSources: List<DataSourceInfo>
├── health: HealthInfo
├── environment: EnvironmentInfo
├── loggers: LoggersInfo
├── flyway: FlywayInfo
├── config: ConfigInfo
├── scheduledTasks: ScheduledTasksInfo
└── server: ServerInfo
```

`RuntimeInfo.machine` (`MachineInfo`, computed once and cached) describes the deployment
environment: the logical processor count, the CPU model and physical-core/SMT topology
(`CpuTopology`; both only where cheaply readable — `/proc/cpuinfo` on Linux, no forking),
total physical memory, the JVM's max heap, the machine's non-local IP addresses with
their reverse-resolved hostnames (`NetworkAddress` — up interfaces only, loopback and
link-local skipped; the lookups run in parallel on virtual threads against a shared ~1s
budget, and one that misses it or only echoes the literal address leaves the hostname
`null`), and the `ContainerRuntime` the process runs under — docker (`/.dockerenv`),
podman (`/run/.containerenv`), kubernetes (`KUBERNETES_SERVICE_HOST`), a generic
container (a `/proc/1/cgroup` marker), or none. Every fact is best-effort: whatever the
host doesn't let the JVM read stays `null`/empty and the dashboard renders no row for it.
The JDK's processor count and total memory are container-aware: inside a limited
container they report the container's share, not the host's. `ContainerRuntime.current()`
is the one detection in the codebase; the autoconfigure module's `LocalDevDetector`
consumes the same cached result.

## Testing

### Test Categories

Two kinds, split by lifecycle (see [`TESTING.md`](TESTING.md)):

- `*Test` — plain unit tests, run by surefire at `test`. The one deliberate exception is
  `PeekabootDefaultsRegistrationTest`, which runs a real non-web `SpringApplication`: it
  boots no server, and what it proves — `spring.factories` registration and
  default-property precedence — belongs in the fast gate.
- `*IT` — anything that boots a server, run by failsafe at `integration-test`.

Where the Spring-backed tests live: `peekaboot-backend`'s suite uses no `@SpringBootTest`
and no embedded server — a bare `AnnotationConfigApplicationContext` or an
`ApplicationContextRunner` is fine where a bean-name lookup or endpoint discovery needs a
real container (`PeekabootActuatorServiceTest`, `ServerUrlResolverTest`).
`peekaboot-spring-boot-autoconfigure` has context-runner unit tests per auto-configuration
plus three `*IT`s that boot its own `TestApplication` (`DevToolbarAutoConfigurationIT` and
`PeekabootOffIT` as `@SpringBootTest`, `StartupBannerIT` through `SpringApplicationBuilder`)
and one `*Test` that runs a real non-web `SpringApplication` to prove the
`spring.factories` registration (`PeekabootDefaultsRegistrationTest`). Everything
Playwright — the UI suite under `org.peekaboot.testingapp.ui` — lives in
`peekaboot-testing-app`, which boots the sample app and drives the real
dashboard/toolbar/overlay in a headless browser; `peekaboot-frontend` has no test sources
of its own, that suite covers it.

### Testing Auto-Configuration

Use `ApplicationContextRunner` with `FilteredClassLoader` for unit tests, as
`PeekabootAutoConfigurationTest` does to prove a missing actuator endpoint class disables
the whole configuration:

```java
contextRunner
    .withPropertyValues("peekaboot.enabled=true")
    .withClassLoader(new FilteredClassLoader(HealthEndpoint.class))
    .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(PeekabootController.class);
    });
```

For integration tests that verify auto-configuration ordering, use real OpenTelemetry:

```java
@SpringBootTest(classes = TestApplication.class, webEnvironment = RANDOM_PORT)
@ActiveProfiles("integration")
class DevToolbarAutoConfigurationIT {
    // Uses spring-boot-starter-opentelemetry for real Tracer bean
    // Verifies auto-configuration creates beans in correct order
}
```

## Key Design Decisions

1. **No external dependencies for tracing**: Works without Zipkin, Jaeger, or other collectors
2. **Micrometer-based**: Uses Micrometer's `Tracer` API for trace context on the request path; only the Logback appender reads MDC (see *Micrometer Tracer Integration*)
3. **Spring Events**: Uses `ApplicationEventPublisher` instead of custom event bus
4. **Bucketed Storage**: `InMemoryTraceStore` handles spans, logs, and request data across three buckets — All, Errors and Slow — each an insertion-ordered map capped at its own size and evicting its oldest trace once full. Errors and Slow hold references to the same bundles as All, so once a trace qualifies it can outlive its own eviction from All. See [peekaboot.org/docs/tracing](https://peekaboot.org/docs/tracing/) for bucket sizing, the slow-trace threshold, and the `bucket=all|errors|slow` filter.
5. **Actuator not web-exposed**: All data accessed in-process through an internal `WebEndpointDiscoverer`; `PeekabootEndpointExposureOutcomeContributor` makes the endpoint beans available without `management.endpoints.web.exposure` (see "In-Process Actuator Invocation")
6. **Plain bounded maps for storage**: Memory is bounded by the three bucket caps and the per-trace span and log caps, with no cache library
7. **Shadow DOM**: Toolbar cannot interfere with host application
8. **Lowest-priority defaults**: Apps can always override peekaboot settings
9. **Shared frontend design system, one override point**: The dashboard, toolbar, and
   trace-detail overlay consume the same `tokens.css`/`base.css`/`components.css` (see
   "peekaboot-frontend" above and `peekaboot-frontend/README.md`) instead of each
   maintaining its own palette and primitives. `tokens.css` is the single documented
   place a consuming application overrides to re-theme every surface.

## Auto-Configuration Ordering

`DevToolbarAutoConfiguration` requires specific ordering to ensure the `Tracer` bean exists:

```java
@AutoConfiguration(
    after = PeekabootAutoConfiguration.class,
    afterName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration"
)
```

The `afterName` attribute (string-based) is used where the referenced auto-configuration
lives in a module this one does not compile against at all — Boot's OpenTelemetry tracing
module here, `spring-boot-micrometer-metrics` for the `CompositeMeterRegistryAutoConfiguration`
edge in `InsightsAutoConfiguration`. Boot reads ordering edges from the class metadata
without loading the named classes, which is why `TracingInterceptorAutoConfiguration` can
use a class literal (`after = ObservationAutoConfiguration.class`) for a module the pom
marks `<optional>`: the class only has to exist at compile time.

## Known defects

Known gaps and the decisions still waiting on a call live in
[`IMPROVEMENTS.md`](IMPROVEMENTS.md) §1 and §2; what was closed, and why, is in its §5.
