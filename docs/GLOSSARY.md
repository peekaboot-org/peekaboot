# Peekaboot Glossary

The names the code uses: classes, fields, wire shapes, and the places where the source and the
UI call one thing by two different words. Terms a user meets have their definition on the
website. Those entries give the spelling in the source and link out, rather than defining the
term twice and letting the two copies drift.

## Trace vocabulary

### Trace
There is no `Trace` class. A trace is a `traceId` and whatever is filed under it, in three
shapes: `TraceDataBundle` while the store is writing, `TraceData` when the bundle is read back
in one go (`TraceDataBundle.snapshot()`: the spans flat and creation-ordered, the root, the
window, the async membership, the failure flag and the dropped-span count), and `TraceResource`
once mapped for the API, its `TraceSummary` and `TraceDetail`. Only the third leaves the process.
`TraceTree` is the mapper's internal step towards it and is never served. What lands in the store is on the site:
[what gets captured](https://www.peekaboot.org/docs/traces/#what-gets-captured).

### Span
Two records, one concept. `SpanData` is the store's copy of an exported OpenTelemetry span:
`spanId` and `parentId`, `Map<String, String>` tags, a nullable Micrometer `Span.Kind` (OTel's
`INTERNAL` has no Micrometer constant and maps to null), and a `creationOrder` minted in export
order. `SpanNode` is the mapped tree node the API serves: the same `Span.Kind` and tag map,
plus `children`, `issues` and a masked `statement`. Issues hang off the node and `SpanData` carries
none. A span's logs are the entries of `TraceDetail.logs` naming its `spanId`.

### Root Span
`TraceDataBundle` chooses it, once: the earliest-created stored span without a parent, else the
earliest-created one continuing a remote caller's trace (its parent remote and not stored, with
deduplication redirects resolved), else none. A trace has none while its request is still
running, and once the span cap evicted its root (see `TraceData.complete`).
`snapshot()` carries that choice to `TraceTreeMapper` as `TraceData.rootSpan`, beside
`TraceData.complete`, and `listingRoots()` answers the same rule without a copy so the list
filters can classify a bundle without building a tree.
`TraceDetail.rootSpan` is the mapped `SpanNode` at the top. While it is null, `TraceDetail.orphans`
holds the stored subtrees instead.

### Root Action Type
`RootActionType`, serialised by constant name. `RootActionTypes.of`
assigns one from the root span's kind and tag prefixes. MAINTENANCE alone also reads the parent
id, the pool rule's fixed contextual name and the Redis rule's tag values. Labels and icons live
only in the frontend's `shared/root-actions.js`, keyed by constant name. A reader's view of the types is on the site:
[trace types](https://www.peekaboot.org/docs/traces/#root-action-type).

The first rule that matches wins:

1. `MESSAGE_CONSUMER`: CONSUMER kind, or any `messaging.*` tag on a span that is not PRODUCER.
   A producer root carries the same tags as a consumer, so the tags alone do not say which way
   the message went.
2. SERVER kind: `HTTP_REQUEST` for any `http.*` tag or the unprefixed `method` and `uri` pair,
   else `RPC_CALL` for any `rpc.*` tag, else `HTTP_REQUEST` again as the likeliest inbound call.
3. `ASYNC_TASK`: the `peekaboot.async` marker tag. It comes before the null-kind catch-all
   because the entry span carries no kind.
4. `SCHEDULED_JOB`: both `code.function` and `code.namespace`. Only Spring's
   `DefaultScheduledTaskObservationConvention` sets that pair, so only a `@Scheduled` method
   fired by Spring's scheduler matches. Quartz or a plain `ScheduledExecutorService` falls
   through to `UNKNOWN` or whatever else its tags match.
5. `MAINTENANCE` for Redis connection upkeep: CLIENT kind, no parent id, `db.system=redis` and a
   `db.operation` in `RootActionTypes.REDIS_UPKEEP_COMMANDS`. Those are what Lettuce's
   connection handshake sends (`HELLO`, `AUTH`, `PING`, `SELECT`, `READONLY`, `CLIENT`) and what
   Boot's Redis health indicators send (`INFO`, `CLUSTER`). The reactive health indicator's
   command takes a parent only from the Reactor context and the handshake runs under no span, so
   both arrive parentless whenever the dashboard reads health in-process or a connection opens.
   Any other parentless Redis command stays `DATABASE`.
6. `DATABASE`: CLIENT kind with any `db.*` tag.
7. `MAINTENANCE` for connection pool upkeep: CLIENT kind, no parent id, the name `connection`
   and any `jdbc.datasource.*` tag. The parent check keeps both `MAINTENANCE` rules to spans
   that ran outside any trace. The store never roots a trace at a span with a local parent, but
   `RootActionTypes.of` classifies whatever span it is handed.
8. `UNKNOWN`: everything else, and a null root. A non-SERVER root with `http.*` or `rpc.*` tags
   lands here on purpose: it is an outbound call, not a request this application served.

`MAINTENANCE` is the one constant `TraceFilter.DEFAULT_VIEW_TYPES` leaves out, so a
listing request naming no type gets every other type. The Traces tab lifts that exclusion with its
Excluded checkbox (`#traces-filter-exclude-maintenance`, `excluded=none` in the URL).
`rootActionType=*` asks for the store as it stands. Separately, the listing leaves out a
trace's own row while `ListingRoots.fragment` is set (spans stored, trace not complete), which is
a different suppression from the default view.

### Root Operation
`TraceSummary.rootOperation` is `rootSpanData.name()` verbatim: no reformatting, no case change.
As a filter it is `TraceFilter.matchesRootOperation`, a case-insensitive substring
match with a `Class.method` suffix fallback so a fully-qualified scheduled-task target still
matches a bean-name span name.

### Async Subtree
A span carrying the marker tag `AsyncTaskMarker.TAG_KEY` (`peekaboot.async`), plus every
descendant of it. `AsyncMembership` holds that membership for each `TraceDataBundle`;
`listingRoots()` returns the entry points alone, a member whose resolved parent is not a
member, together with the root under the same lock. An entry point classifies as `RootActionType.ASYNC_TASK`, gets a listing row of
its own carrying `TraceSummary.subtree` as a `SubtreeView(rootSpanId, enclosedByStoredTrace)`, and
is flagged on the tree as `SpanNode.asyncEntry`, which is what the Spans tab collapses and
re-bases by. What a reader sees:
[background work](https://www.peekaboot.org/docs/traces/#background-work).

### Event Listener Span
The span Peekaboot raises around a call of an application's `@EventListener` method, observation
`EventListenerMarker.OBSERVATION_NAME` (`peekaboot.event.listener`), named
`<SimpleClassName>#<method>`. Raised only inside another observation, so it is always an ordinary
child span and never a trace root. The mechanism, its ordering and what it skips are in
*Event Listener Spans* in [`ARCHITECTURE.md`](ARCHITECTURE.md).

### Trace Outcome and Span Status
`TraceOutcome {OK, ERROR, IN_PROGRESS, INTERRUPTED}` on the trace, `SpanStatus {OK, ERROR}` on each
span, both serialised by constant name. A trace is `IN_PROGRESS` until its root arrives, and
`INTERRUPTED` instead once its run has ended: a trace restored from an earlier run of the
application, whose root will never arrive. A complete whole trace is `ERROR` when the store's sticky
failure flag (`TraceData.failed`) is set or one of its reasons is a failure. The flag is the Errors
bucket's own rule: a span failed or answered 5xx, or an ERROR log arrived. An async row is `ERROR`
only when its own subtree's reasons hold a failure. See [trace status](https://www.peekaboot.org/docs/traces/#trace-status).

### Trace Reason
`TraceSummary.reasons` lists `TraceReason(kind, spanId, detail)`s, built by `TraceReasons`: an
`EXCEPTION` per failed span, a `SERVER_ERROR` per SERVER span answering 5xx, an `ERROR_LOG` per
ERROR log, then one `SLOW` once the duration reaches `peekaboot.tracing.slow-trace-threshold-ms`.
The first three are failures. The span and log caps bound them, so an evicted failure leaves the
trace `ERROR` without its reason. `TraceSummary.buckets` names the Errors and Slow buckets listing it.

### Issue
`SpanNode.issues` holds `TraceIssue`s. Each is a `SpanIssue(type, severity, spanId, value,
threshold, unit, message)` or a `RepeatedQueryIssue`, which adds `statement`, `count`, `totalMs`
and `spanIds`. `IssueType`'s constants are `SLOW`, `VERY_SLOW`, `SLOW_QUERY`, `REPEATED_QUERY`
and `CLIENT_ERROR`; `IssueSeverity` has two, and it and `IssueUnit` serialise lowercase through
`@JsonValue`. The UI colours a span's duration by `severity` alone (`severity.js`'s
`issueSeverity`), never by `type`, and only for issues with `unit: ms`.

Detection is `IssueDetector`, called from `TraceResources`. `TraceTreeMapper` leaves
`issues` empty on every node it builds. SLOW and VERY_SLOW judge `selfMs`, so a slow child
marks only itself. `selfMs` reads high on a trace whose span cap dropped some children, since
a dropped child covers nothing. REPEATED_QUERY compares statements after `SqlNormalizer` and
groups queries by their direct parent span. datasource-micrometer records a JDBC connection
span beside its query, so an N+1 outside a transaction still shares one parent. CLIENT_ERROR is a
warning on a server root that answered 4xx, with the status as `value`, 400 as `threshold` and
`unit: status`. The outcome and the Errors bucket ignore it. Firing conditions:
[issues](https://www.peekaboot.org/docs/traces/#issues). Thresholds and property names:
[peekaboot.tracing](https://www.peekaboot.org/docs/configuration/#issue-thresholds).

### Trace Bucket
`TraceBucket {ALL, ERRORS, SLOW}`, the three independent maps inside `InMemoryTraceStore` and
the `bucket` query parameter of the listing endpoint. Membership is decided on write. Errors is
add-only; Slow follows the synchronous window both ways, since a late async entry can lower it.
A trace stays in Errors or Slow after ageing out of All. A trace enters Errors on a failed span,
a server span's 5xx or an ERROR log. `TraceStore.bucketsOf` names the Errors and Slow buckets
listing a trace. Definitions and thresholds:
[the three buckets](https://www.peekaboot.org/docs/traces/#the-three-buckets).

### Trace Run
One start of the application, named by its `startedAt`: the `ApplicationReadyEvent`'s timestamp,
the instant the lifecycle log stamps the run with, so `summary.run` matches a row of
`/api/lifecycle/runs`. `InMemoryTraceStore` keeps the run in progress as a `TraceRun`; each bundle
keeps the run it was first stored in. A trace captured before the run reported ready learns the
start once it does. Only the trace journal's restore begins a run (`TraceStore.beginRun`).

### Untraced Log
A captured log line whose MDC carried no trace id: written at startup, on a thread outside any
trace, or after a request's trace ended (Tomcat's own error line). `InMemoryTraceStore` keeps the
newest `peekaboot.tracing.max-untraced-logs` (1000) in an `UntracedLogRing` beside the buckets.
They count in no bucket and fail no trace. The log search serves them with `traceId` and
`spanId` null.

### Error digest
`ErrorDigest` counts every failed trace the application completed, one `DigestedError` per
cause class, route and top application frame, the frame without its line number, so the same
failure stays one entry when an edit moves its line. Each entry keeps the count, the first and
last trace's start times, the last message and trace id, and the starts of the last ten runs it
occurred in. It outlives the traces: one noisy failure can fill the Errors bucket and evict the
rest, while the digest keeps up to 500 entries. `GET /peekaboot/api/errors` serves it.

## Where the code and the UI use different words

Each row is a place a grep for the UI's word will not find the code, or the reverse.

| In the code | On screen or on the wire | Note |
|---|---|---|
| `metrics` on `/api/features`, `MetricsInfo`, `GET /api/metrics` | **Meters** tab | The tab module is `tabs/meters.js` and its `isAvailable` reads `features.metrics`. |
| `rootOperation` | **Target** in the Traces tab's filter banner | The banner shortens the value to its last two dot-segments, as the Scheduled Tasks tab does (`shortTarget`). |
| `rootActionType`, `rootOperation` query parameters | `type`, `op` in the dashboard hash | `traces.js` translates between them; the hash is the shareable form. |
| a `SLOW` entry in `TraceSummary.reasons` | **SLOW** badge | `isSlowTrace` in `shared/trace-stats.js` reads it. On a whole trace the Slow bucket admits by the same rule; an async row is judged on its own window while its buckets are the trace's. |
| `TraceOutcome.ERROR` | **ERROR** badge | Rendered instead of SLOW, never beside it. |
| `TraceSummary.counts.droppedSpans` above zero | **TRUNCATED** badge | The per-trace span cap dropped a real span. |
| `HttpRequest.Controller.className` | `class` on the wire | Renamed by `@JsonProperty("class")`. |
| `IssueSeverity.WARNING`, `.ERROR` | `warning`, `error` | Lowercased by `@JsonValue`. |
| `IssueUnit.MS`, `.QUERIES`, `.STATUS` | `ms`, `queries`, `status` | Lowercased by `@JsonValue`. |
| `RootActionType` constant names | Title-case labels and icons | Held only in `shared/root-actions.js`. |
| tab module id `scheduled-tasks` | payload key `scheduledTasks` | Two spellings of the same tab, one in the hash, one in `ActuatorInsightsResponse`. |

`SharedModuleIT` pins the vocabularies that do line up (`ROOT_ACTION_TYPES`, `ISSUE_SEVERITIES`,
`TASK_TYPES`, `MIGRATION_STATES`, `CHANGE_SET_EXEC_TYPES`, `LOG_LEVELS`, the `Features` keys) so they cannot drift apart
silently. A new mirrored vocabulary belongs there too.

## Internal representations

### Trace Data Bundle
`TraceDataBundle` is the store's per-trace container: spans keyed by id in arrival order, the
correlated logs, the request metadata, the failure flags, the dropped-span and dropped-log counts and the parent-redirect table left
behind by span deduplication. It is never serialised. `TraceData` is the flat, creation-ordered
view of its spans that the mappers consume.

### Synchronous Window
`TraceDataBundle.synchronousWindow()`, the window over the spans outside any async subtree, held
by `AsyncMembership` beside the bundle's full window. `snapshot()` carries it as
`TraceData.duration`, so a trace's duration in the listing, the detail header, the waterfall
denominator and Slow-bucket admission is this number rather than the full window. The two agree
for every trace with no async span. What may lower it, and the maintenance rules, are in *Async
Subtree Timing* in [`ARCHITECTURE.md`](ARCHITECTURE.md).

### Insights rings
The Insights tab's history is ring buffers, not a time-series database. Per series,
`SeriesRings` holds one `DoubleRing` of raw samples for level 0 and one `StatsRing` per coarser
level. `AggregateStats` is one aggregated window: `min, max, avg, median, p90, p95, p99` plus a
`samples` count. The SSE events and the full view's `stats` carry the seven. `samples` is what
the next roll-up weights its average by, and what a series summary's `samples` totals. A missing
tick is stored as `NaN` (level 0) or `AggregateStats.EMPTY` (higher levels) and serialises as
JSON `null`, so a gap stays a gap.

Every sample is in its series' declared unit: `SeriesSampler` serves a `percent` series' ratio
meter from 0 to 100 and reads a time gauge in milliseconds, so the rings, the snapshot, the SSE
events and the API agree.

`SeriesSampler` looks its meters up in the registry on every tick, not once at startup, because
Micrometer registers meters lazily. `http.server.requests` does not exist before the first
request, and a new URI adds a tag combination later still. A series whose meters never resolve
still costs a lookup per tick.
Once a meter or subtract-meter has stayed unresolved for three ticks, `SeriesSampler` logs it,
once per outage: at WARN for a series the operator's panel file wrote, at DEBUG for one exactly
as shipped, since an application may lack a shipped meter. Tiles log nothing.

Overview's stat tiles bypass all of it. `TileTracker` holds one `volatile double` per tile and
samples on read.

### Target (scheduled tasks)
`ScheduledTaskInfo.target` is the fully qualified method name of a `@Scheduled` method
(`net.example.TaskService.processItems`), read from Actuator's `scheduledtasks` endpoint. The
Scheduled Tasks tab shows the last two dot-segments and puts the full value in the `title`.

A task's `target` does not equal its traces' `rootOperation`. `rootOperation` is the root span's
own name, typically `task <bean>.<method>` when Spring's scheduler fired it.
`TraceFilter.matchesRootOperation` bridges the mismatch with the `Class.method` suffix
fallback, which is what makes the tab's "View traces" link
(`dashboard/tabs/scheduled-tasks.js`) land on that one task's runs.
`TraceFiltersIT.schedulerTracesLinkArrivesFiltered` is the end-to-end proof.

Beware the third meaning: the Traces tab's own filter banner labels `rootOperation` **Target**,
which is the span name, not this field.

## Wire shapes

### Trace Resource and Trace Summary
`TraceResource(summary, data)` is one trace on the wire, from `GET /peekaboot/api/traces/{traceId}`
and as each row of `GET /peekaboot/api/traces`. `data` is a `TraceDetail(rootSpan, orphans, logs,
queries, httpExchange)`, null on a listing row unless the request asked for `view=full`
(`TraceView`). `TraceSummary` is what an agent reads first and what the toolbar, the Traces tab's
rows and the overlay header show: `traceId`, `startTime`, `durationMs`, `complete`,
`rootActionType`, `rootOperation`, `request(method, route, path, statusCode)`, `outcome`,
`buckets`, `reasons`, `cause`, `issues` (every span's, in tree order), `hotspots`, `counts` and
`subtree`.
`counts` is `spans`, `queries(count, totalDurationMs)`, `logs(count, errorCount, warnCount)`,
`droppedSpans` and `droppedLogs`. `route` is the route tag as recorded; null when the request
recorded none, or only a placeholder such as `NOT_FOUND`. Under Boot's default static-resource
mapping (`spring.web.resources.add-mappings`), a request no controller matched carries `/**`.
The listing is a `TraceListResponse(traces, bucketCounts, filteredBucketCounts, next)`.

`cause` is a `TraceSummary.Cause(type, message, applicationFrame, spanId)` from `TraceCauses`.
The deepest failing span with a stack trace wins, then the first ERROR log with one, then the
deepest failing span that names its exception's type or message, then the first ERROR log, then
the first SERVER span answering 5xx. It is null where the trace did not fail, where the caps
evicted what failed it, or where its only failure is a span that names nothing.

`hotspots` lists up to three `TraceSummary.Hotspot(spanId, name, selfMs)` from `Hotspots`, the
spans with the most self time above zero, largest first. Of equal self times the span earlier
in tree order wins. A subtree row reads only its own subtree's spans.

### Insights problem
`InsightsDataResponse.problems` holds `InsightsProblem(rule, severity, series, value, threshold,
unit, message)`s, in the shape of a trace issue: `severity` is the issues' `IssueSeverity`, `unit`
the panels' `Unit`. `ProblemRule` names the eight checks and the series each reads. A rule whose
series is absent or holds no sample in the window is skipped, so the list holds only what
reached a threshold; an empty list does not say a skipped rule is healthy. Thresholds and their
defaults: [peekaboot.insights](https://www.peekaboot.org/docs/configuration/#peekabootinsights).

### Bucket Counts
`BucketCounts(all, errors, slow)`. Every listing response carries it twice: `bucketCounts` for
the whole store and `filteredBucketCounts` for the traces matching every filter this request
names. The second is null only when the request filtered nothing at all, which
a request naming no type does not, since it still gets the default view's filter. The pair is
what lets the bucket chips show `filtered / total`.

### Cursor
The opaque `cursor` a listing endpoint reads and hands out as `next` (`Cursors`, base64url
text). It carries the store's `numbering` first. A log search cursor names the last served line's
store `sequence`. A trace listing cursor names the last served row's `RowPosition`; the next page
lists what sorts after it as the store stands then, so no older row repeats or goes missing. A
request whose root ends while a client pages lists where its trace entered the bucket, which can
be below a page already served. A cursor carries no filter: sent with other filters, it pages
through what those match below the same position. A restart creates a new store under a new
numbering and renumbers every row and line, restored or not, so a cursor from before it is
refused with a 400 on `cursor`.

### Route Stats
`RouteStats(method, route, count, errorCount, p50Ms, p95Ms, maxMs, latestErrorTraceId,
latestSlowTraceId, issueCounts)` is one row of `GET /peekaboot/api/routes`. It covers the complete
HTTP requests the All bucket holds for one method and route pattern. `route` is null for requests
recorded without a route tag, or with only a placeholder such as `NOT_FOUND`. Under Boot's
default static-resource mapping (`spring.web.resources.add-mappings`), a request no controller
matched carries `/**`, a GET and a POST alike. `RouteListResponse.Window(from, to, traceCount)`
bounds the requests counted by their earliest and latest start and counts them.

### HTTP Exchange
`HttpExchange(HttpRequest request, HttpResponse response)`, built from a `RequestCompletedEvent`
by `HttpExchangeMapper` and carried on `TraceDetail.httpExchange`. `HttpResponse` is `status` and
`headers`. `HttpRequest` holds four flat fields and two nested records:

| Field on the wire | Contents |
|---|---|
| `method`, `path` | Verb and request URI. |
| `query` | The query string, values masked. |
| `headers` | Request headers, values masked. |
| `controller.class`, `controller.method` | The resolved `HandlerMethod`. |
| `params.query`, `params.form` | Parsed parameters, values masked. |

`controller.class` is `HttpRequest.Controller.className` in Java, renamed on the wire by
`@JsonProperty("class")`, since `class` is a Java keyword.

Request bodies and uploads are not captured; see *Servlet Filters* in
[`ARCHITECTURE.md`](ARCHITECTURE.md) for what a body-capture pass has to solve first.

### Trace Log and Query Info
`TraceLog(traceId, spanId, timestamp, level, logger, thread, message, stackTrace, hiddenFrames,
applicationFrames)` is one captured log event, correlated by the trace and span ids frozen in its
MDC, and named by its logger and its thread. `stackTrace` is the captured throwable, null where there was none; `hiddenFrames` and
`applicationFrames` are the line ranges folding marked in it. It appears once on the detail
response, in `TraceDetail.logs`, its `spanId` resolved to the span that survived deduplication. A log
with no span id belongs to no span. The log search serves the same record in
`LogSearchResponse.logs`, where an untraced line has `traceId` and `spanId` null.

`QueryInfo(spanId, dbSystem, dataSourceName, durationMs, rowCount, statement)` is one query span.
Its `statement` is the one its span carries as `SpanNode.statement`, null when the instrumentation
recorded none; the query is still listed. `dataSourceName` is
the Hikari pool name (`jdbc.datasource.pool`) unless Hikari generated it (`HikariPool-1`), else
datasource-proxy's `jdbc.datasource.name`, null when neither is tagged.
`TraceDetail.queries` is served on the single-trace endpoint and on listing rows asked for with
`view=full`.

### SQL Statement
`SqlStatement(text, formatted, parameters, omittedParameterSets)` is the statement a query span
carries, served as `SpanNode.statement` and `QueryInfo.statement` and null on every other span. Every part is masked.
`text` is the SQL as captured, `formatted` the same SQL laid out by `SqlFormatter` (null when
none is available or it could not lay the SQL out), and `parameters` the bind values as SQL
literals, one list per parameter set. `omittedParameterSets` counts the sets a capped batch
left out of that list. The Spans and Queries tabs both render it through `shared/sql-view.js`.

## The word "insights"

The word carries two unrelated features in the URL space, plus a prose shorthand.

- **The Insights tab** is the aggregated-metric charts: `dashboard/tabs/insights.js`, gated on
  the `insights` feature flag and backed by `InsightsController` under the **prefix**
  `/peekaboot/api/insights/{config,data,stream}`.
- **The insights suffix** marks one enriched read model, `/peekaboot/api/actuator/all/insights`.
  Nothing to do with the tab. `PeekabootController` serves it as `ActuatorInsightsResponse`. An
  `InsightsSource` bean is one actuator reading, keyed by endpoint id, that
  `ActuatorInsightsResponse` is built from, so its name follows the suffix too. The traces
  endpoints are `/peekaboot/api/traces` and `/peekaboot/api/traces/{traceId}`, served as
  `TraceListResponse` and `TraceResource`. `TraceInsightsService` keeps the old word.
- **Insights Data** in prose means the enriched shape itself: `TraceResource` and `SpanNode`
  rather than `TraceData` and `SpanData`.

So `/api/insights/**` and a path ending in `insights` are two different features that happen to
share a word. A path with `insights` in the middle is the tab's; one ending in it is a read
model. Neither is a version of the other, and they disappear under different conditions: the
prefix form goes away without a `MeterRegistry` bean, the suffix form does not.

### Backend-for-Frontend (BFF)
The pattern behind the suffix. The backend assembles, correlates and masks before the frontend
sees anything: logs already resolved to the span that emitted them, issues already detected, SQL
already masked, thresholds already resolved onto `/api/features`. The frontend renders what it is
given and keeps no analysis of its own. Peekaboot's UI is served from the same process it observes, so
there is no second service to hold that logic.

## UI surfaces

### Dev toolbar
The bar injected into HTML responses by `DevToolbarFilter`. `ToolbarShell` renders the server-side
markup and `ToolbarDataProvider` embeds the JSON blob `toolbar.js` reads before it calls home.
Two payload shapes: `ToolbarSummary` for a page that came out of a traced request, `IdleMode` for
one that did not.

### Dashboard
The standalone UI at `/peekaboot/`. "Dashboard" always names the whole thing, never one tab. Its
tab modules register in strip order: Overview, Insights, Lifecycle, Traces, Meters,
Environment, Flyway, Liquibase, Loggers, Config, Scheduled Tasks. Each exports `id`, `label` and `render`,
and optionally `isAvailable` to hide its own strip button.

### Overview Tab
The landing tab, and the consumer of most of `ActuatorInsightsResponse`: the insights stat tiles,
build/git/Spring/Java/OS info, the Machine card, JVM Defaults, the datasource cards beside it,
the memory and storage meters, and the health banner with its component grid. The Machine card
holds CPU count and model, total memory, max heap (`memory.heapMax`, the heap meter's figure), the
detected container runtime, and the machine's non-local addresses with reverse-resolved
hostnames. Distinct from the Insights tab.

### Trace Detail (the overlay)
The overlay over a single trace, `trace-detail/trace-detail.js`, not the `TraceDetail` record it
renders. It runs in a shadow root and is
loaded two ways: lazily by `toolbar.js` through a dynamic import, eagerly by the dashboard's
static import. Its four tabs are `request`, `spans`, `queries` and `logs`. The last three show
badges from `TraceSummary.counts`. Its header names `TraceSummary.cause` with a link to the
cause's span, and the Spans tab lists `TraceSummary.hotspots` above the tree, each linking to its
row.
