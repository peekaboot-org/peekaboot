# Peekaboot Architecture

> This file and its siblings in `docs/` are for people changing the code. Consumer
> documentation (quick start, configuration, security, the dashboard tour) lives at
> [www.peekaboot.org](https://www.peekaboot.org).

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
`peekaboot.enabled`'s resolved value: on for an IDE or `spring-boot:run` launch, off everywhere
else, and an explicit setting wins in either direction. Switching Peekaboot on deliberately in
a shared environment therefore writes nothing to that host's `$HOME`.

`<app>` is `<groupId>.<artifactId>` from `build-info.properties`, sanitized to `[A-Za-z0-9._-]`.
A build that publishes no build information falls back to `spring.application.name`, and an
application with neither to `application`. Coordinates rather than the name, so two
applications sharing a `spring.application.name`, or having none, keep their history apart. An
explicit `peekaboot.storage.dir` is used verbatim, with no per-application subdirectory
appended.

`StorageDirectory` only resolves this path; it never touches the disk. While
`peekaboot.storage.enabled` is `false`, `StorageDirectory.file(...)` returns empty and neither
store ever writes. Every write goes through `OwnerOnlyFiles.replaceAtomically`, the single
implementation of the mechanism: it creates the parent directory, writes to a sibling `*.tmp`,
moves that over the target with `ATOMIC_MOVE` (a plain replace where the file system offers
none), and deletes the temporary if the write fails. On a POSIX file system the directory is
created `rwx------` and every file `rw-------` regardless of the process umask, though an
existing directory keeps its permissions. The temporary is always created fresh with
`CREATE_NEW` after removing whatever sat at its path, so a symlink planted there is deleted,
never followed. Failure handling stays with the stores: on `IOException` each logs and
continues in memory rather than taking the host application down over an unwritable `$HOME`.

Both stores assume one application instance per directory. Two instances pointed at the same
`peekaboot.storage.dir` overwrite each other's files, and a half-written file is discarded on
the next read, so the cost is lost history rather than corruption. Peekaboot is a development
tool and coordinating instances is out of its scope: give each instance its own
`peekaboot.storage.dir` if you run several against one home directory.

### `insights.snapshot`

The insights ring buffers' contents: every level's geometry (`intervalMs`, `size`,
`endEpochMs`, `count`) and, per series, one ring per level. Level 0 has a single `values`
column; every coarser level has eight aggregate columns (`min`, `max`, `avg`, `median`, `p90`,
`p95`, `p99`, `samples`). `samples` is carried alongside the seven the API exposes because the
next roll-up weights its average by it. `InsightsSnapshotCodec` reads and writes that as a
small versioned binary format: magic `"PKIN"`, a schema version, then the header and body.

`InsightsSnapshotStore` owns the file. It writes through `OwnerOnlyFiles.replaceAtomically` on
each boundary of `peekaboot.insights.persistence.interval` (default: the coarsest level's own
interval), and once more synchronously at shutdown after the collector has stopped, so the
final write sees quiesced rings. A run that never ticked skips the write rather than
overwriting a good file with an empty one.

The snapshot is a cache, never a source of truth, so anything wrong with it costs only the
history. Four things count as wrong: a bad magic number, a schema version this build doesn't
know, a ring geometry that no longer matches `peekaboot.insights.levels`, and an age past
`peekaboot.insights.persistence.max-age` (default: the coarsest level's span). The file is then
deleted and the rings start empty, exactly as they would with storage off. Every length read
from the file is checked against a plausibility bound before it is used to allocate, so a
corrupt file cannot provoke an oversized allocation.

Loading never delays startup. `InsightsSnapshotStore.beginLoad()` submits the parse to a
virtual thread and returns. Each of the collector's level threads then runs a one-shot restore
just before its *first* write, gated by `SnapshotRestoreBarrier` so only the thread that
arrives first applies the snapshot and a level-1 roll-up can never land ahead of the restore it
depends on. That thread waits up to 5 seconds for the parse, and a snapshot arriving later is
discarded rather than layered on top of live samples. Restoring a level's `endEpochMs` is what
turns the outage into a visible gap on the chart. `fillMissed()` then pads exactly the missed
interval at that level's next tick or roll-up, capped at the ring size. It is the same code
that pads a suspended laptop or a stalled sampler.

A run whose restore did not complete keeps its history, because `InsightsSnapshotStore` refuses
to write over a file it never claimed. A process that started with a handful of samples cannot
replace a full retention window. The guard is deliberately blunt: it is keyed on whether the
restore completed, not on how full the live rings have since become, so a run whose restore
timed out never persists again however long it then runs. Lifting it once every level's ring is
as full as the persisted header says would keep the protection and end the starvation. Until
then the file on disk is only ever as good as the last run that restored it.

### `lifecycle.jsonl`

The application's start/stop history: one JSON object per line, at most 1000 events (oldest
dropped first), read on a virtual thread rather than the startup path. `LifecycleEventFile`
rewrites the whole file through the same `OwnerOnlyFiles.replaceAtomically` on every change.
That is cheap at this size (at most 400 KB for the full 1000) and it removes both a
partial-line corruption window and a second trim code path. A line that fails to parse is
skipped on read; the rest of the file still loads.

A start event carries its epoch timestamp, its pid, and the `BuildProperties`/`GitProperties`
entries the two projections read: `version`, `time`, `branch`, `commit.id`, `commit.id.full`,
`commit.id.abbrev`, `build.version` and `build.time`. No others, because a git remote URL can
carry the token it was cloned with and the building user's mail address is personal data. A stop
event carries only its own timestamp and pid, since its build belongs to the start it follows,
which the log still remembers.

The log's in-memory half runs independently of `peekaboot.storage.enabled`. With storage off,
`LifecycleEventLog` still records the current run's start and stop in memory and serves them
from there; `LifecycleEventFile` is never consulted. That is why a dashboard with persistence
switched off still shows one start marker for the run in progress.

### API and dashboard

`GET /peekaboot/api/lifecycle/events`, gated by `peekaboot.lifecycle.enabled`, serves the log
as start/stop markers. For each start, `LifecycleEvents` compares it against the previous start
already in the log: `version`, `branch`, `commitId`/`shortCommitId` and `buildTimeEpochMs` are
included only when they differ, and all four are present on the log's first entry. A start
whose predecessor is itself a start, with no stop between, is flagged `uncleanPrevious`.
Computing the diff server-side gives the browser one definition of "what changed" instead of
reimplementing it wherever a marker is drawn. The dashboard fetches these alongside the ring
data from `/peekaboot/api/insights/*` and draws them as restart markers over the same charts.

`GET /peekaboot/api/lifecycle/runs`, gated by the same property, is the second projection over
the same log. A chart marker only wants to say what's new, so `LifecycleEvents` nulls a field
the moment it repeats the previous start. A table row has no previous cell to inherit from, so
`LifecycleRuns` makes every run stand on its own. `version`, `branch`, `shortCommitId` and
`buildTimeEpochMs` are carried forward from the last start that reported them. `changed` is the
separate, explicit answer to whether this run was a deployment: which of `version`, `branch`
and `commit` differ from the run before. The oldest run has no predecessor, so it is never a
deployment.

A run's `stoppedAtEpochMs` and `ranForMs` are null, not a guess, when its start has no matching
stop: a `kill -9` writes nothing, so when that run ended is genuinely unknown. `downForMs`, the
gap since the previous run stopped, is null for the same reason whenever the previous run has
no recorded stop. Null there means unknowable, not zero. The 1000-event cap means a long-lived
application's retained history begins mid-cycle, and the oldest surviving event is often a stop
whose own start already aged out. That orphaned stop is normal, and it carries a real
timestamp, so the downtime between it and the next start is still reported.

The Lifecycle tab renders `/runs` as a table alongside `/events`' markers on the charts, 20
rows to a page. Paging is client-side over the single fetch: the cap bounds the response at
roughly 500 runs, small enough to hold in the browser rather than adding pagination parameters
to the endpoint.

### Shutdown banner

`ApplicationStoppedListener`, mirroring the ready banner's frame, logs a banner on
`ContextClosedEvent` reporting how long the application ran. Its uptime is measured from
`ApplicationContext.getStartupDate()`, the context's own refresh, rather than the
`ApplicationReadyEvent` timestamp the lifecycle log and chart markers use. It stays available
even when the context closes before the application ever became ready, at the cost of a few
seconds' divergence from "ready" that the banner's own label names.

## Architecture Overview

Everything runs inside the host application's own process and on its own event bus.

```
  DevToolbarFilter ──┐                                 ┌── RequestCaptureFilter
  (HTML injection)   └──► Micrometer Tracer ◄──────────┘   (request metadata)
                             tracer.currentSpan()

  OtelSpanExporter          ──► SpanDataEvent          ┐
  PeekabootLogbackAppender  ──► LogCapturedEvent       ├─► ApplicationEventPublisher
  RequestCaptureFilter      ──► RequestCompletedEvent  ┘    (Spring's own)
                                                                   │
                                                                   ▼   @EventListener
                                                       TraceStoreEventListener
                                                                   │
                                                                   ▼
                                       TraceStore (InMemoryTraceStore): three bounded,
                                       insertion-ordered maps, the All/Errors/Slow buckets
                                                                   │
                                                                   ▼
                                       PeekabootController: /peekaboot/api/*
```

## peekaboot-backend

### Package Structure

```
org.peekaboot.backend/
├── actuator/parsed/        # Typed beans for actuator responses (ActuatorResponseParser)
├── config/                 # PeekabootProperties, UiTracingProperties, PeekabootWebConfig (+ ApiSecurityHeadersInterceptor: no-store/nosniff on /peekaboot/api/**), PeekabootJson (+ its message converter), PeekabootPaths
├── controller/             # PeekabootController: /peekaboot/api/* (actuator data, metrics, traces, features)
├── devtoolbar/             # ToolbarShell (server-rendered markup), ToolbarDataProvider
├── domain/                 # Domain models, one sub-package per dashboard concern
│   ├── application/, config/, datasource/, environment/, flyway/, health/,
│   ├── insights/ (incl. ActuatorInsightsResponse, the dashboard's aggregate DTO), lifecycle/, loggers/, metrics/, runtime/, scheduledtasks/, server/
│   ├── features/           # Features: the /api/features payload, flags plus the effective slow thresholds
│   └── trace/              # TraceTree, SpanNode, HttpExchange, TraceTabSummary, IssueType, SpanStatus, IssueSeverity, ...
├── filter/                 # DevToolbarFilter, RequestCaptureFilter, ContentBufferingResponseWrapper
├── insights/               # Metric ring buffers: InsightsCollector, StatsRing, snapshot codec/store, IntervalBoundary (the boundary-aligned schedule the level threads and the snapshot writer share)
│   ├── config/             # InsightsProperties, panels file (PanelDef, SeriesDef, TileDef)
│   └── web/                # InsightsController, InsightsSsePublisher: /peekaboot/api/insights/*
├── lifecycle/              # Ready/stopped banners, LifecycleEventLog + LifecycleEventFile, build info, DataSourceMetadata, HikariPoolInfo (the one Hikari reference, wired only with HikariCP present), ByteFormat (the one byte formatter; insights uses it too)
│   └── web/                # LifecycleController: /peekaboot/api/lifecycle/*
├── log/                    # PeekabootLogbackAppender
├── mapper/                 # Data transformation
│   ├── actuator/           # Actuator → domain mappers, CronDescriber (cron expressions in words, for ScheduledTasksMapper)
│   └── trace/              # TraceTreeMapper, IssueDetector, QueryExtractor, DbSpans (the one "is this a query span" predicate)
├── masking/                # MaskingEngine (one bean, declared by PeekabootAutoConfiguration), MaskingRules, TagMasker, TreeMasker,
│                           # ConnectionParamsMasker: the one place "is this key/value sensitive" is decided; see www.peekaboot.org/docs/security
├── service/                # ActuatorInsightsService, TraceInsightsService, PeekabootActuatorService, ...
├── storage/                # StorageDirectory resolves peekaboot.storage.dir; OwnerOnlyFiles does owner-only, symlink-safe writes (see Persisted state)
├── tracing/                # In-memory tracing
│   ├── bridge/otel/        # OtelSpanExporter
│   ├── config/             # PeekabootTracingProperties
│   ├── event/              # SpanDataEvent, LogCapturedEvent, RequestCompletedEvent
│   ├── interceptor/        # TracingHandlerInterceptor
│   └── store/              # TraceStore, InMemoryTraceStore, TraceDataBundle, SpanDuplicateMatcher,
│                           # TraceBucket, TraceStoreEventListener
```

### Tracing Flow

1. **Span capture**: `OtelSpanExporter` receives finished spans from the OpenTelemetry SDK and publishes a `SpanDataEvent`
2. **Storage**: `TraceStoreEventListener` listens via `@EventListener` and forwards to `TraceStore` (`InMemoryTraceStore`), three insertion-ordered bounded maps: the All, Errors and Slow buckets
3. **Log capture**: `PeekabootLogbackAppender` reads `traceId`/`spanId` from the event's frozen MDC map (Logback events carry MDC state, not a live span) and drops events without a `traceId`
4. **Request metadata**: `RequestCaptureFilter` uses `Tracer.currentSpan()` to correlate request details
5. **Query**: `TraceInsightsService` reads `TraceStore` directly by `TraceBucket` (ALL/ERRORS/SLOW), then assembles and enriches the tree (see *Trace Assembly and Enrichment*)
6. **Thresholds**: `IssueDetector` raises SLOW/VERY_SLOW/SLOW_QUERY at `UiTracingProperties`' thresholds and sets `TraceTree.slow`, the Traces tab's badge. `GET /peekaboot/api/features` publishes those thresholds plus the Slow bucket's `slowTraceThresholdMs` (`Features`), so the frontend colours by the same numbers instead of keeping a copy

### Servlet Filters

| Filter | Order | Registered when | Purpose |
|--------|-------|-----------------|---------|
| `RequestCaptureFilter` | `HIGHEST_PRECEDENCE + 100` | a `Tracer` **and** a `TraceStore` bean exist | Captures request/response metadata for traces, and sets the `Server-Timing` header |
| `DevToolbarFilter` | `LOWEST_PRECEDENCE` | a `Tracer` bean exists | Renders the toolbar (markup, inlined styles, data) into HTML responses via `ToolbarShell`, and loads the script that enhances it |

Both map `/*`. `RequestCaptureFilter`'s order is documented in the registration: it sits
*inside* Boot's `ServerHttpObservationFilter` (`HIGHEST_PRECEDENCE + 1`), so the server span is
current when it runs, and ahead of Spring Security, so a request the security chain rejects is
still captured. `DevToolbarFilter` is innermost; it wraps the response in a
`ContentBufferingResponseWrapper` and injects its markup into the buffered body. Both
registrations live only in `DevToolbarAutoConfiguration`, so neither filter runs while
`peekaboot.dev-toolbar` is off.

`PeekabootPaths` is the one place Peekaboot's URL space is defined: the `/peekaboot` prefix,
the excluded prefixes, and those same exclusions as MVC patterns for the tracing interceptor.
The exclusions are five prefixes, `/static/`, `/webjars/`, `/peekaboot/`, `/error/` and the
resolved management base path (`/actuator/` by default). Note what is not there: Boot's other
default static locations, `/public/`, `/resources/` and `/META-INF/resources/`. As MVC patterns
each prefix gains a `**` suffix, and `/x/**` matches bare `/x`, so `/error` is excluded while
`/errors` stays an application path.

Everything in `PeekabootPaths` is relative to the servlet context. The filters match on the
container's mapped path (`getServletPath() + getPathInfo()`, decoded and normalised) rather
than the raw request URI, so neither a `server.servlet.context-path` nor a
`/x/../peekaboot/...` spelling can hide the dashboard's own calls from the exclusion. Every URL
the toolbar writes into a page (script, stylesheets, dashboard links, the `basePath` in its
data blob) is prefixed with `request.getContextPath()`, and `shared/api.js` derives the same
base path from its own module URL, so neither surface needs configuring.

`PeekabootPathsAutoConfiguration` constructs the single bean with the resolved
`management.endpoints.web.base-path` and threads it into both filters, the interceptor
registration and `OtelSpanExporter`'s span skip. A base path of `/` excludes nothing extra:
there is no prefix to tell those requests apart by. The bean also carries the resolved
`server.servlet.context-path`, because a span's HTTP path tag still has that prefix in front;
`isExcludedRequestPath` strips it before matching, so actuator and Peekaboot spans are skipped
the same with and without one.

Without the toolbar on, a trace still carries a basic method/path/status summary
(`summary.request`), read off the root span's HTTP tags by `HttpSpanTags`. That class knows all
three naming schemes that reach the store. Boot's default server-request observation uses
`method`, `status`, `uri` for the route pattern, and `http.url`, the request URI and therefore
the path shown. The current OpenTelemetry names are `http.request.method`, `url.path` and
`http.response.status_code`; their superseded spelling is `http.method`, `http.target` and
`http.status_code`. The overlay reads `summary.request` rather than the tags, so the browser
never has to know those names. Headers, query/form parameters and the resolved controller
class/method are never available without the toolbar, and neither are correlated logs (see
*Log Capture*).

Request/response body content and uploaded file names have fields reserved on `HttpExchange`
that `RequestCaptureFilter` never populates. They are not captured, dev-toolbar or not. Those
fields, and the `null`/`List.of()` arguments passed in their place, are the seam a body-capture
implementation would fill; they are kept deliberately, and filling them is its own design pass.
Capture is the easy half. Masking is the hard one, because `MaskingEngine`'s key-name rules
have no key names to judge in a body: a JSON body is structure, a form post is pairs, an upload
is bytes. Sizing needs a cap and the truncation marker the Request tab already renders off
`body.truncated`.

### Server-Timing Header

`RequestCaptureFilter`, dev-toolbar-only per above, sets a `Server-Timing` response header
carrying the current trace context in W3C `traceparent` form, before invoking the filter
chain:

```
Server-Timing: trace;desc="00-<traceId>-<spanId>-<traceFlags>"
```

Setting it before the chain runs ensures the header is present even when downstream
handling commits the response early. The toolbar's own idle-mode script, used on pages like
Swagger UI that have no request of their own to report, reads this header off `fetch()`
responses to pick up a trace id for a call it didn't otherwise see. Nothing stops any other
tool that can read response headers from doing the same.

### BFF Pattern

The backend implements a Backend-for-Frontend pattern:

1. **Raw actuator data**: `PeekabootActuatorService` invokes actuator endpoints in-process (see below)
2. **Typed parsing**: `ActuatorResponseParser.parse(...)` converts raw JSON to typed beans
3. **Domain mapping**: individual mappers transform to domain models
4. **Aggregation**: `ActuatorInsightsService` combines all data for the dashboard

`ConfigMapper` flattens a nested `@ConfigurationProperties` value to one property per leaf under
a dotted key (`hikari.maximumPoolSize`), indexing list elements the way Spring's own property
syntax does (`servers[0].host`), so the Config tab's filter matches nested keys and values.
Masking runs on the tree first (`TreeMasker`, by leaf key), so a sensitive key anywhere in it
arrives as the single masked leaf its subtree collapsed to.

### The two `insights` URL shapes

Two unrelated things share the word, and only the position in the path tells them apart.

| Shape | Endpoints | What it is |
|-------|-----------|------------|
| `insights` as a **suffix** | `GET /peekaboot/api/actuator/all/insights`, `GET /peekaboot/api/traces/insights`, `GET /peekaboot/api/traces/{traceId}/insights` | The BFF enrichment above: raw data assembled into a domain aggregate. Served by `PeekabootController` |
| `/api/insights/` as a **prefix** | `GET /peekaboot/api/insights/config`, `/data`, `/stream` | The metric ring buffers behind the Insights tab. Served by `InsightsController`, which needs a `MeterRegistry` bean and has nothing to do with the BFF pipeline |

Neither is a version of the other, and they disappear under different conditions: the prefix
form goes away with the `MeterRegistry`, the suffix form does not. `docs/GLOSSARY.md` draws the
same line for the word; this is the line for the routes.

### JSON on the wire

Peekaboot's REST responses and its insights SSE events are serialised by
`PeekabootJson.MAPPER`, a plain default Jackson mapper, never by the application's own Jackson
bean. The dashboard reads camelCase names, tests some fields with `!== null` (`loggers.js`)
and parses every `Instant` as an ISO-8601 string. An application that sets
`spring.jackson.property-naming-strategy`, `default-property-inclusion=non_null` or
`datatype.datetime.write-dates-as-timestamps` must not silently reshape that.

`PeekabootJsonMessageConverter` (registered first by `PeekabootWebConfig`) claims every return
value whose class lives under `org.peekaboot.backend.`. The scope has to be the value's type,
because Spring MVC picks converters per return value, not per controller.
`InsightsAutoConfiguration` hands the same mapper to `InsightsSsePublisher`. The default mapper
writes byte for byte what an unconfigured Boot application writes, so a host with default
Jackson settings sees the same bytes either way
(`PeekabootJsonMessageConverterTest.writesCamelCaseNullsAndIsoInstants` pins the wire shape).
Every Peekaboot response goes through it, `PeekabootController.getFeatures()`'s `Features`
record and `InsightsController`'s 400 body included, both under that package prefix.

### In-Process Actuator Invocation

Peekaboot never calls `/actuator/*` over HTTP. `PeekabootActuatorService` builds its own
`WebEndpointDiscoverer` with empty endpoint filters, bypassing
`management.endpoints.web.exposure` *filtering*, and invokes each endpoint's READ operation
directly (`operation.invoke(...)`). Data therefore flows without any actuator endpoint being
reachable over the web.

Health is the one exception to the discoverer path. The web operation the discoverer finds
under `health` is `HealthEndpointWebExtension`, which applies
`management.endpoint.health.show-details`. That setting belongs to the application's own public
`/actuator/health` and Peekaboot must not widen it, so the service reads the `HealthEndpoint`
bean itself. `HealthEndpoint.health()` always carries the components and their details, so the
dashboard has full health while `/actuator/health` keeps answering anonymous callers with the
aggregate status only. `ActuatorResponseParser` accordingly parses the bare `HealthDescriptor`
shape (`status`, `components`, `groups`), not a `WebEndpointResponse` wrapper. A composite
contributor nests its children under a further `components` map. Spring's `db` becomes one as
soon as there are two DataSources, as does any custom composite. `HealthMapper` flattens those
children to `db/<name>` for the dashboard's single list, and `DataSourceMapper` reads each
DataSource's own child status rather than the composite's aggregate.

Bypassing exposure filtering is not enough on its own. Spring Boot only *creates* an endpoint
bean when `@ConditionalOnAvailableEndpoint` matches: exposed via web, JMX (only when
`spring.jmx.enabled=true`), or a custom contributor. Boot's default web exposure is `health`
only, so without help the `env`, `configprops`, `loggers`, `flyway` and `scheduledtasks` beans
would never exist. `PeekabootEndpointExposureOutcomeContributor`, registered under
`EndpointExposureOutcomeContributor` in `META-INF/spring.factories` (a Spring Boot 3.4+
extension point), closes that gap: while `peekaboot.enabled=true` it reports every web-capable
endpoint as exposed, so the beans are created. The HTTP mapping under `/actuator` still applies
`management.endpoints.web.exposure`, so no endpoint becomes reachable over the web. With Spring
defaults only `/actuator/health` answers HTTP while the dashboard has full data.

Whether those `env` and `configprops` values arrive unmasked is a separate question from
whether the beans exist; see *Default Properties*.

See [www.peekaboot.org/docs/security](https://www.peekaboot.org/docs/security/) for what this
exposure model means in practice for securing a deployment.

## peekaboot-frontend

Static resources served from `/peekaboot/ui/`, backing three UI surfaces that share one design
system: the standalone dashboard, the dev toolbar injected into host-app pages, and the
trace-detail overlay. This section covers only the headline decisions;
`peekaboot-frontend/README.md` has the shared-layer split, the shadow-DOM delivery mechanism,
theme resolution, accessibility invariants, the ids the test suite depends on, and the file
inventory of every module under `META-INF/peekaboot/ui/`.

### Design Principles

- **No build step**: plain HTML/CSS/JS, ES modules
- **Shared design system, three surfaces**: the dashboard document consumes
  `assets/tokens.css`/`base.css`/`components.css` directly, the toolbar's and overlay's shadow
  roots via `attachSharedStyles()`. A doubled selector (`:root, :host { ... }`) lets the
  identical stylesheet apply in both contexts, so no surface carries its own palette,
  `escapeHtml`, duration thresholds or collapsible-group CSS
- **Shadow DOM**: toolbar and trace-detail overlay isolated from host app styles
- **Responsive dashboard, desktop-first toolbar and overlay**: the toolbar wraps and the
  overlay's gantt reflows below 768px, but both assume a desktop viewport for their full layout
- **Lazy loading**: trace-detail overlay JS loaded only on first use (dynamic `import()` from
  `toolbar.js`; eager static import from `dashboard/main.js`)
- **Theme support**: `--pk-*` custom properties resolved once in `shared/theme.js` from
  `localStorage['peekaboot-theme']`, falling back to `prefers-color-scheme`, shared across all
  three same-origin surfaces

`peekaboot-frontend/README.md` covers the fill/text token split and which tokens have to
change together. Changing one without its pair is how an accessibility regression ships.

## peekaboot-spring-boot-autoconfigure

Auto-configuration classes that wire everything together. The nine `@AutoConfiguration` classes
are registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; the three
hooks that run before or outside the application context are registered in
`META-INF/spring.factories`.

| Class | Registered via | Purpose |
|-------|----------------|---------|
| `PeekabootAutoConfiguration` | `.imports` | Core beans: controller, services, mappers, web config |
| `DevToolbarAutoConfiguration` | `.imports` | Toolbar and capture filter registrations, `LogbackAppenderRegistrar` |
| `PeekabootLifecycleAutoConfiguration` | `.imports` | Ready/stopped listeners, lifecycle event log and its API |
| `PeekabootStorageAutoConfiguration` | `.imports` | `StorageDirectory`; no web/actuator conditions |
| `InsightsAutoConfiguration` | `.imports` | Metrics collector/service, SSE fan-out, insights controller; needs a `MeterRegistry` |
| `PeekabootTracingAutoConfiguration` | `.imports` | Tracing properties and store |
| `OtelTracingAutoConfiguration` | `.imports` | OpenTelemetry span exporter |
| `TracingInterceptorAutoConfiguration` | `.imports` | Tracing handler interceptor and its MVC registration (see *Handler and View Spans*) |
| `PeekabootPathsAutoConfiguration` | `.imports` | The single `PeekabootPaths` bean (see *Servlet Filters*) |
| `PeekabootDefaultsEnvironmentPostProcessor` | `spring.factories` (`EnvironmentPostProcessor`) | Local-dev detection for `peekaboot.enabled`, `peekaboot.dev-toolbar` and `peekaboot.storage.enabled`, the `show-values` keys on a local servlet run, and the default property values |
| `PeekabootEndpointExposureOutcomeContributor` | `spring.factories` (`EndpointExposureOutcomeContributor`) | Makes actuator endpoint beans available without web/JMX exposure |
| `LogbackCaptureReinstaller` | `spring.factories` (`ApplicationListener`) | Re-attaches the log-capture appender after Boot's `LoggingApplicationListener` re-initialises Logback |
| `LocalDevDetector` | (package-private helper) | The local-launch heuristic behind the post-processor (see *Conditional Loading*) |

Every `@Bean` method across these is `@ConditionalOnMissingBean`, matched by name for the
anonymous `WebMvcConfigurer` registration and by the deduced generic type for the
`FilterRegistrationBean`s, so an application bean of the same type or name replaces any
Peekaboot default instead of colliding with it.

### Conditional Loading

Seven of the nine classes carry the same two class-level conditions, the servlet guard and
the master switch: `PeekabootAutoConfiguration`, `PeekabootPathsAutoConfiguration`,
`DevToolbarAutoConfiguration`, `TracingInterceptorAutoConfiguration`,
`PeekabootTracingAutoConfiguration`, `OtelTracingAutoConfiguration` and
`InsightsAutoConfiguration`.

```java
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
```

Each adds its own on top: `PeekabootAutoConfiguration` the `HealthEndpoint` and `InfoEndpoint`
classes, `DevToolbarAutoConfiguration` `peekaboot.dev-toolbar`,
`TracingInterceptorAutoConfiguration` the `ObservationRegistry` class and bean,
`InsightsAutoConfiguration` a `MeterRegistry` bean and `peekaboot.insights.enabled`, and
`OtelTracingAutoConfiguration` the OpenTelemetry SDK's `SpanExporter` class.

`PeekabootAutoConfiguration` registers the servlet-only `PeekabootWebConfig` next to the
controllers, services and actuator wiring, all as explicit `@Bean` methods whose names yield
the class-derived bean names `ServerUrlResolver`'s dashboard check relies on. It needs the
servlet guard because `PeekabootWebConfig implements WebMvcConfigurer`, a servlet-only type.

That guard prevents dead beans, not a crash. Without it a WebFlux or non-web application with
`peekaboot.enabled=true` would register the controller, services and mappers with nothing
servlet-specific ever invoking them. `ApplicationContextRunner` confirms the context still
starts cleanly: only `WebMvcConfigurationSupport` calls back into `WebMvcConfigurer`, and it is
itself only wired up in a servlet context. `PeekabootTracingAutoConfiguration` and
`OtelTracingAutoConfiguration` carry the guard for the same reason: everything that reads the
trace store is servlet-only, so they would otherwise fill an `InMemoryTraceStore` for nobody.

`PeekabootLifecycleAutoConfiguration` and `PeekabootStorageAutoConfiguration` carry no servlet
guard, because the ready/stopped summaries, the run history and the storage directory must work
in a plain non-web application. Only the lifecycle API's `LifecycleController` bean is
servlet-gated on its own. `PeekabootDefaultsEnvironmentPostProcessor` splits the same way:
activation, storage and value-visibility detection are web-type independent, while
`peekaboot-defaults.yml` and the dev-toolbar defaults are skipped off-servlet.

`PeekabootTracingAutoConfiguration`, `OtelTracingAutoConfiguration` and
`TracingInterceptorAutoConfiguration` additionally require `peekaboot.tracing.enabled` (default
on): handler and view observations are tracing, and with it off there is no store for them to
land in. `DevToolbarAutoConfiguration` does not read that property at all. Its capture half,
the `RequestCaptureFilter` registration and the Logback appender registrar, is
`@ConditionalOnBean(TraceStore.class)` instead, so with `peekaboot.tracing.enabled=false` the
toolbar is still injected into every page with nothing captured behind it.

There is no `matchIfMissing` fallback for `peekaboot.enabled` or `peekaboot.dev-toolbar`. Both
default from `PeekabootDefaultsEnvironmentPostProcessor` into a `peekabootDetection` property
source at lowest precedence, so any explicit application setting wins in either direction. The
toolbar keys on the same local-development detection as `peekaboot.enabled`, not on
`peekaboot.enabled`'s resolved value, so turning Peekaboot on deliberately in a shared
environment does not inject the toolbar into every page or widen `/actuator/env` as a side
effect.

`LocalDevDetector` starts from the heuristics Spring Boot DevTools itself uses and adds two
signals of its own, checked in order:

1. A native image resolves to `false` before anything else is checked.
2. If the context class loader is DevTools' `RestartClassLoader` (the `restartedMain` thread
   DevTools relaunches on), only the container check below is left to decide. DevTools
   relaunches like that for a local launch, so the class loader stands in for the class-path
   proof. A Jib image and Boot's `extract` layout ship DevTools whenever the application has it
   as a runtime dependency, so a container marker still resolves `false`.
3. Otherwise the result is `true` only when *all* of these hold. The thread is named `main`.
   Its context class loader is the JDK's own `AppClassLoader`, not Spring Boot's
   `LaunchedClassLoader` (a packaged, executable jar) and not a servlet container's webapp
   loader (a deployed war). And the call stack carries no `org.junit.runners.`,
   `org.junit.platform.`, `org.springframework.boot.test.`, Boot AOT processor or
   `cucumber.runtime.` frames. That last check is what tells a `@SpringBootTest` run apart from
   a genuine local launch, which shares the thread name and the class loader.
4. Those three hold for *every* exploded-classpath launch, so two more signals decide
   (`LocalDevDetector.LaunchSignals`, read from the JVM and the host, injectable in tests).
   `java.class.path` must contain a build tool's output directory: an entry ending in
   `target/classes`, `build/classes/java/main`, `build/classes/kotlin/main`,
   `build/classes/groovy/main`, `build/classes/scala/main` or `bin/main`, or containing
   `out/production/`. An IDE, `spring-boot:run` and `bootRun` always put one there; a Jib image
   (`/app/classes`) and Boot's `extract` layout (a thin jar with a `Class-Path` manifest) never
   do. And `ContainerRuntime.current()` must report `NONE`: no `/.dockerenv`, no Podman
   `/run/.containerenv`, no `KUBERNETES_SERVICE_HOST`, and no `/proc/1/cgroup` naming `docker`,
   `kubepods` or `containerd`.

So an IDE run, `mvn spring-boot:run` and `gradle bootRun` default to on. A `java -jar` of the
packaged artifact, a war in a servlet container, a native image, an AOT-processed build, a
test, a Jib image, the `extract` layout, a plain `java -cp` of jars and anything inside a
container default to off. What is left for an explicit `peekaboot.enabled=false` is the one
shape the signals cannot separate: a build output directory mounted into a non-container
process that is not a developer's own launch.

`peekaboot.lifecycle.enabled` is read by a `@ConditionalOnBooleanProperty` condition, since it
has to be evaluated before any Peekaboot bean exists, and is also bound as
`PeekabootProperties.Lifecycle.enabled` so it carries configuration metadata and shows up on
the Config tab like every other switch.

### Default Properties

`PeekabootDefaultsEnvironmentPostProcessor` loads three yml resources with lowest precedence,
all overridable by an app's own `application.yml`:

- `peekaboot-defaults.yml`. Enables full observability, but only when Peekaboot is enabled
  *and* the application is a servlet web application, since everything that would read it is
  servlet-only. The web type is `spring.main.web-application-type` where the environment
  carries it, and otherwise the one `SpringApplication.getWebApplicationType()` deduced,
  because Boot binds `spring.main.*` onto the application only after the post-processors have
  run. The dev-toolbar defaults sit behind the same check.
- `peekaboot-no-push-defaults.yml`. Applies unconditionally, even when Peekaboot itself is
  disabled, to keep telemetry from leaving the process by default.
- `peekaboot-dev-toolbar-defaults.yml`. Applied only when the dev toolbar resolves on. Sets
  `management.opentelemetry.tracing.export.schedule-delay` to `200ms` (Spring default `5s`),
  trading export throughput for latency so a trace is readable in the toolbar while the
  developer is still looking at the page.

`management.endpoint.env.show-values` and the `configprops` equivalent are deliberately not in
`peekaboot-defaults.yml`. The `peekabootDetection` property source sets them instead, and only
on a local servlet run whose `peekaboot.enabled` also resolves true, never as an explicit
`never` off-local. Off-local, Spring's own default (`never`) applies and every property masks
exactly as it would without Peekaboot at all: `server.port` and `os.name` along with the
passwords. Emitting an explicit `never` would pin Spring's current default into applications
that never asked Peekaboot to decide it.

`show-values` and `peekaboot.enable-unmasking` are two switches, and only the first is about
visibility. `show-values` decides whether the actuator hands Peekaboot a real value at all;
`enable-unmasking` decides only whether the dashboard's reveal step is offered and honoured.
`PeekabootController.resolveUnmask` combines it with the request's `unmask` parameter, and
neither half suffices alone. With `show-values` at `never` a reveal has nothing left to reveal.

How those sources are contributed matters. Boot moves `defaultProperties`, the source
`SpringApplication.setDefaultProperties` fills, to the end of the environment once every
post-processor has run, so a source merely appended last would outrank it. Where that source
exists Peekaboot folds its entries into it, underneath the application's own, so
`SpringApplicationBuilder.properties("peekaboot.enabled=false")` wins like any other setting.
Where it does not, Peekaboot's four named sources are appended last, in this order:
`peekabootDetection`, `peekabootNoPushDefaults`, `peekabootDefaults` and
`peekabootDevToolbarDefaults`. `PeekabootDefaultsRegistrationTest` pins both halves.

## Tracing Integration

### OpenTelemetry Bridge

When OpenTelemetry is on the classpath, `OtelSpanExporter` is registered
(`OtelTracingAutoConfiguration`; the class condition is on the auto-configuration, not the bean
method):

```java
@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
// ... the servlet guard, peekaboot.enabled and peekaboot.tracing.enabled
public class OtelTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OtelSpanExporter otelSpanExporter(...) { ... }
}
```

It is one more `SpanExporter` bean alongside whatever Boot's own OpenTelemetry
auto-configuration registered, standing up no tracing stack of its own and copying every
finished span it sees into `TraceStore` as well. Turning `peekaboot.tracing.enabled` off leaves
the rest of the app's OpenTelemetry setup (sampling, other exporters such as Zipkin, Jaeger or
an OTLP backend) untouched.

It skips Peekaboot's own requests span by span, using the same five `PeekabootPaths` prefixes
the filters and the interceptor use. A child of such a request carries neither the path tag nor
the route name, so skipping a *root* also publishes a `TraceDiscardedEvent`;
`TraceStoreEventListener` turns that into `TraceStore.discard`, which drops the trace from all
three buckets. Everything else becomes a `SpanData` published as a `SpanDataEvent`. An error is
recorded only for a span whose status code is `ERROR`: the message is the status description,
falling back to the `exception` event's `exception.message` when empty, and the class is that
event's `exception.type`, or `ERROR` where the span recorded no exception event.

`tracing/bridge/otel` is the only bridge. There is no Brave/Zipkin one, so an application wired
to Micrometer Tracing's Brave bridge instead of the OpenTelemetry SDK captures nothing.

### Handler and View Spans

`TracingHandlerInterceptor` is the only instrumentation Peekaboot adds to the request path. It
raises two Micrometer observations, so every exporter the application has configured sees them,
not just Peekaboot's:

| Observation | Raised in | Tags |
|-------------|-----------|------|
| `spring.handler` | `preHandle`, once per request | `handler.type` (low cardinality: the handler class's simple name), `handler.name` (high cardinality: `BeanType.methodName` for a `HandlerMethod`, else the handler class's simple name) |
| `spring.view.render` | `postHandle`, only when the handler returned a resolvable view | `view.type` (always `template`), `view.name` (the view name, or the `View` implementation's simple class name when the handler returned a `View` instance) |

A `@ResponseBody` or REST controller renders no view, so those requests carry a handler span and
no view span at all.

`preHandle` opens an observation **scope** and stashes it on the request. The scope stays open
across the handler, so observations started inside it (JDBC, HTTP clients) nest under the
`spring.handler` span rather than under the HTTP server span. That nesting is what makes the
overlay's span tree show queries beneath the controller method instead of flat under the
request, and it is the reason to keep a scope rather than just time the handler.

An `ASYNC` re-dispatch is skipped so the handler span is not counted twice, and
`afterConcurrentHandlingStarted` ends it on the thread that started it. A handler exception
skips `postHandle`, so `afterCompletion` ends the handler observation and records the error.

`TracingInterceptorAutoConfiguration` registers two beans: the interceptor, and an anonymous
`WebMvcConfigurer` named `tracingInterceptorConfigurer` that adds it with
`addPathPatterns("/**")` and `excludePathPatterns(peekabootPaths.excludePatterns())`, which is
where the five exclusion prefixes reach the interceptor. That configurer's
`@ConditionalOnMissingBean` matches by *name*, not by type: a type check on `WebMvcConfigurer`
would let any of the application's own configurers back the registration off.

### Micrometer Tracer Integration

On the request path (`RequestCaptureFilter` and `DevToolbarFilter`) Peekaboot reads the trace
context from Micrometer's `Tracer` API, via `tracer.currentSpan()`. That keeps the filters
compatible with Boot's tracing auto-configuration whether or not MDC propagation is enabled.
Log capture is the exception: `PeekabootLogbackAppender` runs inside Logback, where the only
trace context available is the event's frozen MDC map, so it reads `traceId`/`spanId` from
there and depends on Micrometer's MDC propagation (Boot's default) being on.

Peekaboot only ever *reads* trace context. There is no `Propagator`, no `TextMapPropagator` and
no header-injection code anywhere in the repo.

### Log Capture

`PeekabootLogbackAppender` publishes a `LogCapturedEvent` per captured event;
`TraceStoreEventListener` forwards it to `TraceStore`, which stores by traceId, and
`TraceInsightsService` attaches them to the trace's spans on read (see *Trace Assembly and
Enrichment*). The appender is attached by the `LogbackAppenderRegistrar` bean, which lives
inside `DevToolbarAutoConfiguration`, so correlated logs require `peekaboot.dev-toolbar=true`.
A trace's Logs tab stays empty without it, independent of `peekaboot.tracing.enabled`.

Capture is off for as long as any application in the JVM is re-initialising Logback. Boot
resets the JVM-wide logger context on every application start, which detaches the appender.
`LogbackCaptureReinstaller` reattaches it on that same event, but a request served in between
is traced with no logs against it. One application never sees this, since its re-initialisation
happens before it serves anything, so this belongs to test suites, where contexts start while
other contexts serve requests. A test asserting on a specific request's log must establish that
the trace carries it rather than assume it (see `docs/TESTING.md`). Closing the window from
outside Logback is not possible: Boot stops the logger context before resetting it, which drops
even reset-resistant listeners.

### Trace Assembly and Enrichment

Three collaborators turn a stored bundle into what the API returns, and the split is easy to
get wrong: `TraceTreeMapper` builds the tree and nothing else.

1. `TraceTreeMapper.map(traceData, truncated)` builds the `TraceTree`. It picks the root span,
   re-parents orphans, masks tags, error messages and query text, classifies the root action
   type and computes the tab summary. It leaves `TraceTree.slow` false, every `SpanNode.issues`
   list empty and every `SpanNode.logs` null. **It attaches no issues and correlates no logs.**
2. `IssueDetector.detectIssues(tree)` fills those issues in and decides `TraceTree.slow`.
   `TraceInsightsService` calls it on both trace endpoints.
3. `TraceInsightsService.enrichWithDetails` adds what only the stored bundle knows: the flat log
   list, the same logs grouped onto the span that emitted them (`groupLogsBySpan` then
   `attachLogsToSpan`), the `HttpExchange` and `QueryExtractor`'s queries. It runs on
   `GET /peekaboot/api/traces/{traceId}/insights` only. The listing endpoint gets step 1, a log
   *count* for the row badges, and step 2, so its trees carry issues but no log list and no
   queries.

`findRootSpan` takes the first span with no parent stored in this trace, falling back to the
first span. `attachOrphansToRoot` then re-parents every other span whose parent is not in the
trace onto that root, so a subtree whose parent has not been exported yet does not silently
vanish. `truncated` is passed into the mapper rather than derived from the span list. It is a
property of how the trace was captured, and the list the mapper sees is already deduplicated
and already capped, so nothing in it can say whether real spans were dropped.

### Span Deduplication

Deduplication runs primarily on write, in `TraceDataBundle.addSpan`. As each span arrives,
`SpanDuplicateMatcher.isDuplicate` collapses a child span into its parent when they share a
name and their tags match once `peer.service` and `jdbc.datasource.name` are ignored. That is
the shape a single operation instrumented by more than one layer produces, for example a JDBC
driver-level span and a `datasource-proxy` span for the same query. The removed span's own
children are re-parented onto the nearest surviving ancestor so the tree stays connected.

`peekaboot.tracing.max-spans-per-trace` (default 500) then caps the already-deduplicated span
count, so the cap counts real, distinct work rather than a double-instrumented call as two
spans against it. A trace still over the cap drops its oldest spans to make room and is flagged
`truncated: true`. That flag is exposed on both `GET /peekaboot/api/traces/insights` and
`GET /peekaboot/api/traces/{traceId}/insights`, and shown as a `TRUNCATED` badge in the trace
list and the detail overlay.

The fold checks both arrival directions on every insertion (see `TraceDataBundle`'s class
Javadoc for why it has to), but `addSpan` returns as soon as the arriving span duplicates its
own already-stored parent, without re-examining that span's children. On a triple-nested chain
whose three spans all duplicate one another, two of the arrival orders therefore leave one
duplicate standing. Two independent properties keep that shape out of reach, either sufficient
alone. It needs three nesting levels, where `SpanDuplicateMatcher.SERVICE_IDENTIFIER_KEYS`
carries one key per datasource decorator and only two decorators exist. And it needs a span to
arrive after its own parent, which the `BatchSpanProcessor`'s child-before-parent end ordering
forbids. Adding a third service-identifier key, or making `SpanDuplicateMatcher.isDuplicate`
non-transitive, brings it within reach. The answer to that is a few lines in `TraceDataBundle`,
never a second deduplication pass beside the write-time one.

### Query Extraction

Database queries aren't captured specially. A query shows up in a trace because the
JDBC/datasource instrumentation on the classpath already emits a span for it, tagged with
`db.*` or `jdbc.query*` attributes. `DbSpans.isQuery` is the one definition of a query span:
the CLIENT side of a database call carrying a `db.*` or `jdbc.query*` tag. `jdbc.*` alone is
not enough, since datasource-proxy's connection and result-set spans carry
`jdbc.datasource.name`/`jdbc.row-count` and are not queries. The predicate is shared by
`TraceTreeMapper` (`summary.queries.count`), `IssueDetector` (SLOW_QUERY and the
HIGH_QUERY_COUNT children count) and `QueryExtractor` (the `queries` list), so the three
numbers a trace reports about its queries are one number; `TraceTreeMapperTest` pins the
equality.

`QueryExtractor` builds each trace's `queries` list from those spans, independently of the span
tree's own names, one entry per query span. A span whose instrumentation recorded no statement
is listed with `sql: null`. `DbSpans.sql` checks tags in priority order:

1. `db.query.text`, the current OpenTelemetry semantic convention, emitted by
   `datasource-micrometer-opentelemetry`, the default stack `peekaboot-testing-app` uses
2. `db.statement`, that convention's superseded spelling, so a library emitting both is read
   by the current one
3. `jdbc.query[N]` (datasource-proxy/Micrometer)
4. only if nothing tagged the span, its own name, and only if that looks like SQL

The same masked text is put on the span itself as `SpanNode.query`, which is what the Spans
tab's SQL toggle shows. `findDbSystem` mirrors this priority for `db.system.name` /
`db.system` / `jdbc.datasource.name` / `peer.service`. Masking is value-patterns only, not
column-aware literal masking (`MaskingRules.VALUE_PATTERNS` carries the reasoning), so a
credential with no provider-recognisable shape sitting in an ordinary column is not caught.
The security page states that as a caveat and tells readers to assume a captured trace carries
plaintext SQL. It is a caveat, not a promise waiting to be strengthened.

Two pipelines render a query and only one depends on `QueryExtractor`. The Spans tab
(`trace-detail/tabs/spans.js`) renders `span.name`, OpenTelemetry's own span-name summary, for
example `SELECT customer_order`. The Queries tab (`trace-detail/tabs/queries.js`) renders
`query.sql`, which is where the tag `DbSpans.sql` picks actually shows up. The overlay opens on
Spans by default (`trace-detail.js`'s `initial: 'spans'`), and `ScreenshotCapture` photographs
both, so `trace-detail-queries-*` is the shipped image demonstrating `QueryExtractor`'s output.

## Data Models

### Trace Domain

```
TraceData
├── traceId: String
├── startTime, endTime, duration
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

### The insights SSE stream

`InsightsSsePublisher` fans the collector's ticks and roll-ups out to every open dashboard over
`/peekaboot/api/insights/stream`. A tick carries series values only; tiles are read from
`/peekaboot/api/insights/config`, not streamed. Each subscriber gets its own bounded send lane
and sender thread, so one wedged peer drops its own events instead of stalling the stream. A
15-second heartbeat keeps idle connections open, and the publisher refuses past
`MAX_SUBSCRIBERS` with a 503.

Emitters carry a five-minute timeout. It only reclaims a peer that vanished without closing its
socket, since the heartbeat and the lane overflow already detect one that is merely wedged.
Every expiry costs a full resync, which for the default 39 series is roughly 393,000 values per
open dashboard at level 1. Thirty minutes would cut that by an order of magnitude with nothing
functional lost; five is the value it was built with, nothing more.

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
environment. It carries the logical processor count, total physical memory and the JVM's max
heap, plus the CPU model and physical-core/SMT topology (`CpuTopology`, both only where cheaply
readable, from `/proc/cpuinfo` on Linux, with no forking). It carries the machine's non-local
IP addresses with their reverse-resolved hostnames (`NetworkAddress`, up interfaces only,
loopback and link-local skipped). Those lookups run in parallel on virtual threads against a
shared ~1s budget; one that misses the budget, or only echoes the literal address, leaves the
hostname `null`. And it names the `ContainerRuntime` the process runs under: docker
(`/.dockerenv`), podman (`/run/.containerenv`), kubernetes (`KUBERNETES_SERVICE_HOST`), a
generic container (a `/proc/1/cgroup` marker), or none. Every fact is best-effort: whatever the
host doesn't let the JVM read stays `null`/empty and the dashboard renders no row for it. The JDK's processor count
and total memory are container-aware, reporting a limited container's share rather than the
host's. `ContainerRuntime.current()` is the one detection in the codebase, and
`LocalDevDetector` consumes the same cached result.

`RuntimeInfo.process` (`ProcessInfo`, computed once through a holder class, since the values
are static for the JVM's lifetime) is the identity the JVM runs under: `username` from
`System.getProperty("user.name")`, `pid` from `ProcessHandle.current().pid()`, plus `uid`,
`gid` and `parentProcesses`. The ids are the **real** uid and gid, taken as the first of the
four on `/proc/self/status`'s `Uid:` and `Gid:` lines. That is a plain file read with no
forking, and it yields `null` wherever the file or the line is absent, which is anything but
Linux. The file is read rather than a directory stat'ed because no file's owner, the working
directory's included, reliably shares the credentials the process runs under.
`parentProcesses` walks `ProcessHandle.parent()` as far up as the JVM is allowed to see,
recording each ancestor's pid and its command reduced to a basename.

`dataSources` comes from `DataSourceMetadata.fromDataSource`, which opens a real `Connection`
per DataSource and reads `DatabaseMetaData`: `getURL()`, `getUserName()`,
`getDatabaseProductName()`, `getDatabaseProductVersion()` and `getDriverName()`. The URL is
then parsed by `net.osslabz.jdbc.JdbcUrlParser` into hosts, database name, database product and
connection parameters. `databaseProduct` is therefore what the *URL* names, so a MariaDB
reached over a `jdbc:mysql:` URL reports MySQL; `databaseProductName` is the driver's own
answer and can disagree. Any exception at all is logged at WARN and yields `Optional.empty()`,
so a DataSource that cannot hand out a connection costs its card and nothing else.

`scheduledTasks` carries each task's last failure verbatim.
`ScheduledTasksMapper.parseException` builds that field as the exception type, a colon and the
exception message, taken straight off the actuator response. There is no `MaskingEngine`
anywhere in that class. An exception message that echoes a JDBC URL, a query or a credential reaches the
dashboard unmasked. Treat it as a known exposure alongside the rest of the model at
[www.peekaboot.org/docs/security](https://www.peekaboot.org/docs/security/).

## Testing

### Test Categories

Two kinds, split by lifecycle (see [`TESTING.md`](TESTING.md)):

- `*Test`: plain unit tests, run by surefire at `test`. The one deliberate exception is
  `PeekabootDefaultsRegistrationTest`, which runs a real non-web `SpringApplication`. It boots
  no server, and what it proves (`spring.factories` registration and default-property
  precedence) belongs in the fast gate.
- `*IT`: anything that boots a server, run by failsafe at `integration-test`.

`peekaboot-backend`'s suite uses no `@SpringBootTest` and no embedded server; a bare
`AnnotationConfigApplicationContext` or an `ApplicationContextRunner` covers the cases where a
bean-name lookup or endpoint discovery needs a real container (`PeekabootActuatorServiceTest`,
`ServerUrlResolverTest`). `peekaboot-spring-boot-autoconfigure` has context-runner unit tests
per auto-configuration, plus three `*IT`s that boot its own `TestApplication`
(`DevToolbarAutoConfigurationIT` and `PeekabootOffIT` as `@SpringBootTest`, `StartupBannerIT`
through `SpringApplicationBuilder`). Everything Playwright lives in `peekaboot-testing-app`
under `org.peekaboot.testingapp.ui`, which boots the sample app and drives the real
dashboard/toolbar/overlay in a headless browser. `peekaboot-frontend` has no test sources of
its own; that suite covers it.

### Testing Auto-Configuration

Use `ApplicationContextRunner` with `FilteredClassLoader` for unit tests, as
`PeekabootAutoConfigurationTest` does to prove a missing actuator endpoint class disables the
whole configuration:

```java
contextRunner
    .withPropertyValues("peekaboot.enabled=true")
    .withClassLoader(new FilteredClassLoader(HealthEndpoint.class))
    .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(PeekabootController.class);
    });
```

For ordering, use a real OpenTelemetry stack rather than a mock `Tracer`:
`DevToolbarAutoConfigurationIT` is a `@SpringBootTest(classes = TestApplication.class,
webEnvironment = RANDOM_PORT)` on the `integration` profile, pulling in
`spring-boot-starter-opentelemetry` so the `Tracer` bean is the real one.

## Key Design Decisions

1. **No external dependencies for tracing**: works without Zipkin, Jaeger or other collectors
2. **Micrometer-based**: Micrometer's `Tracer` API for trace context on the request path; only the Logback appender reads MDC (see *Micrometer Tracer Integration*)
3. **Spring events**: `ApplicationEventPublisher` instead of a custom event bus
4. **Bucketed storage**: three insertion-ordered maps, each capped at its own size and evicting its oldest trace once full. Errors and Slow hold references to the same bundles as All, so a qualifying trace outlives its own eviction from All. [www.peekaboot.org/docs/traces](https://www.peekaboot.org/docs/traces/) has the bucket sizing, the slow-trace threshold and the `bucket=all|errors|slow` filter
5. **Actuator not web-exposed**: all data read in-process through an internal `WebEndpointDiscoverer` (see *In-Process Actuator Invocation*)
6. **Plain bounded maps for storage**: memory is bounded by the three bucket caps and the per-trace span and log caps, with no cache library
7. **Shadow DOM**: the toolbar cannot interfere with the host application
8. **Lowest-priority defaults**: applications can always override Peekaboot settings
9. **One token sheet for three surfaces**: the dashboard, the toolbar and the trace overlay all load the same `tokens.css`, so every colour is defined once and no component hardcodes one. This keeps the CSS honest; it is not a supported theming API, and nothing outside the project is expected to replace the file. See [`peekaboot-frontend/README.md`](../peekaboot-frontend/README.md) for the fill/text pairing rule that constrains any change to it

## Auto-Configuration Ordering

`DevToolbarAutoConfiguration` requires specific ordering to ensure the `Tracer` bean exists:

```java
@AutoConfiguration(
    after = PeekabootAutoConfiguration.class,
    afterName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration"
)
```

The string-based `afterName` attribute is used where the referenced auto-configuration lives
in a module this one does not compile against at all: Boot's OpenTelemetry tracing module
here, and `spring-boot-micrometer-metrics` for the `CompositeMeterRegistryAutoConfiguration`
edge in `InsightsAutoConfiguration`. Boot reads ordering edges from the class metadata without
loading the named classes, which is why `TracingInterceptorAutoConfiguration` can use a class
literal (`after = ObservationAutoConfiguration.class`) for a module the pom marks
`<optional>`. The class only has to exist at compile time.
