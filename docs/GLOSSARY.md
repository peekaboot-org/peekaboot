# Peekaboot Glossary

The names the code uses: classes, fields, wire shapes, and the places where the source and the
UI call one thing by two different words. Terms a user meets have their definition on the
website. Those entries give the spelling in the source and link out, rather than defining the
term twice and letting the two copies drift.

## Trace vocabulary

### Trace
There is no `Trace` class. A trace is a `traceId` and whatever is filed under it, in three
shapes: `TraceDataBundle` while the store is writing, `TraceData` when its spans are read back
flat and creation-ordered (`TraceData.fromSpans`), and `TraceTree` once mapped for the UI. Only
the third leaves the process. What lands in the store is on the site:
[what gets captured](https://www.peekaboot.org/docs/traces/#what-gets-captured).

### Span
Two records, one concept. `SpanData` is the store's copy of an exported OpenTelemetry span:
`spanId` and `parentId`, `Map<String, String>` tags, a nullable Micrometer `Span.Kind` (OTel's
`INTERNAL` has no Micrometer constant and maps to null), and a `creationOrder` minted in export
order. `SpanNode` is the mapped tree node the API serves: `kind` as a plain string, plus
`children`, `issues`, `logs` and a masked `query`. Issues and logs hang off the node;
`SpanData` carries neither.

### Root Span
`TraceTreeMapper.findRootSpan` takes the first span in creation order whose parent is not in the
trace, falling back to the first span. `TraceDataBundle.rootSpan()` is the store-side twin over
stored spans with deduplication redirects resolved, used to classify a bundle for filtering
without building a tree. `TraceTree.rootSpan` is the mapped `SpanNode` at the top.

### Root Action Type
`RootActionType`, eight constants, serialised by name. `TraceTreeMapper.detectRootActionType`
assigns one from the root span's kind and tag prefixes, never from its name. Labels and icons
live only in the frontend's `shared/root-actions.js`, keyed by constant name. The priority rules
and their gotchas are on the site:
[root action type](https://www.peekaboot.org/docs/traces/#root-action-type).

`CONNECTION_POOL` is the one constant `TraceInsightsService.DEFAULT_VIEW_TYPES` leaves out, so a
listing request naming no type gets every other type. `rootActionType=*` asks for the store as
it stands. Separately, `isIncompleteFragment` drops any bundle whose root still carries a parent
id and is not SERVER-kind, which is a different suppression from the default view.

### Root Operation
`TraceTree.rootOperation` is `rootSpanData.name()` verbatim: no reformatting, no case change.
As a filter it is `TraceInsightsService.matchesRootOperation`, a case-insensitive substring
match with a `Class.method` suffix fallback so a fully-qualified scheduled-task target still
matches a bean-name span name.

### Trace Status and Span Status
`TraceStatus {OK, HAS_ERRORS}` on the trace, `SpanStatus {OK, ERROR}` on each span, both
serialised by constant name. `TraceTreeMapper` sets `HAS_ERRORS` when any span has an error
class or message. An error-level log alone does not: it only puts the trace in the Errors
bucket. See [trace status](https://www.peekaboot.org/docs/traces/#trace-status).

### Issue
`SpanIssue(IssueType type, String message, IssueSeverity severity)`, held in `SpanNode.issues`.
`IssueType` has five constants (`SLOW`, `VERY_SLOW`, `ERROR`, `SLOW_QUERY`,
`HIGH_QUERY_COUNT`); `IssueSeverity` has two and serialises lowercase through `@JsonValue`.

Detection is `IssueDetector`, called from `TraceInsightsService`. `TraceTreeMapper` leaves
`issues` empty on every node it builds, and leaves `logs` empty too. Firing conditions:
[issues](https://www.peekaboot.org/docs/traces/#issues). Thresholds and property names:
[peekaboot.ui.tracing](https://www.peekaboot.org/docs/configuration/#peekabootuitracing).

### Trace Bucket
`TraceBucket {ALL, ERRORS, SLOW}`, the three independent maps inside `InMemoryTraceStore` and
the `bucket` query parameter of the listing endpoint. Membership is decided on write, add-only,
and a trace stays in Errors or Slow after ageing out of All. Definitions and thresholds:
[the three buckets](https://www.peekaboot.org/docs/traces/#the-three-buckets).

## Where the code and the UI use different words

Each row is a place a grep for the UI's word will not find the code, or the reverse.

| In the code | On screen or on the wire | Note |
|---|---|---|
| `metrics` on `/api/features`, `MetricsInfo`, `GET /api/metrics` | **Meters** tab | The tab module is `tabs/meters.js` and its `isAvailable` reads `features.metrics`. |
| `rootOperation` | **Target** in the Traces tab's filter banner | The banner also shortens the value to its last dot-segment. |
| `rootActionType`, `rootOperation` query parameters | `type`, `op` in the dashboard hash | `traces.js` translates between them; the hash is the shareable form. |
| `TraceTree.slow` | **SLOW** badge | True when any span carries a SLOW or VERY_SLOW issue. The Slow bucket is a whole-trace threshold. See [the slow badge is not the slow bucket](https://www.peekaboot.org/docs/traces/#the-slow-badge-is-not-the-slow-bucket). |
| `TraceStatus.HAS_ERRORS` | **ERROR** badge | Rendered instead of SLOW, never beside it. |
| `TraceTree.truncated` | **TRUNCATED** badge | Set when the per-trace span cap dropped a real span. |
| `HttpRequest.Controller.className` | `class` on the wire | Renamed by `@JsonProperty("class")`. |
| `IssueSeverity.WARNING`, `.ERROR` | `warning`, `error` | Lowercased by `@JsonValue`. |
| `RootActionType` constant names | Title-case labels and icons | Held only in `shared/root-actions.js`. |
| tab module id `scheduled-tasks` | payload key `scheduledTasks` | Two spellings of the same tab, one in the hash, one in `ActuatorInsightsResponse`. |

`SharedModuleIT` pins the vocabularies that do line up (`ROOT_ACTION_TYPES`, `ISSUE_TYPES`,
`TASK_TYPES`, `MIGRATION_STATES`, `LOG_LEVELS`, the `Features` keys) so they cannot drift apart
silently. A new mirrored vocabulary belongs there too.

## Internal representations

### Trace Data Bundle
`TraceDataBundle` is the store's per-trace container: spans keyed by id in arrival order, the
correlated logs, the request metadata, the `truncated` flag and the parent-redirect table left
behind by span deduplication. It is never serialised. `TraceData` is the flat, creation-ordered
view of its spans that the mappers consume.

### Insights rings
The Insights tab's history is ring buffers, not a time-series database. Per series,
`SeriesRings` holds one `DoubleRing` of raw samples for level 0 and one `StatsRing` per coarser
level. `AggregateStats` is one aggregated window: `min, max, avg, median, p90, p95, p99` plus a
`samples` count. The API and the SSE events see the seven; `samples` stays internal and is what
the next roll-up weights its average by. A missing tick is stored as `NaN` (level 0) or
`AggregateStats.EMPTY` (higher levels) and serialises as JSON `null`, so a gap stays a gap.

Overview's stat tiles bypass all of it. `TileTracker` holds one `volatile double` per tile and
samples on read.

### Target (scheduled tasks)
`ScheduledTaskInfo.target` is the fully qualified method name of a `@Scheduled` method
(`net.example.TaskService.processItems`), read from Actuator's `scheduledtasks` endpoint. The
Scheduled Tasks tab shows the last two dot-segments and puts the full value in the `title`.

A task's `target` does not equal its traces' `rootOperation`. `rootOperation` is the root span's
own name, typically `task <bean>.<method>` when Spring's scheduler fired it.
`TraceInsightsService.matchesRootOperation` bridges the mismatch with the `Class.method` suffix
fallback, which is what makes the tab's "View traces" link
(`dashboard/tabs/scheduled-tasks.js`) land on that one task's runs.
`DashboardTabsIT.schedulerTracesLinkArrivesFiltered` is the end-to-end proof.

Beware the third meaning: the Traces tab's own filter banner labels `rootOperation` **Target**,
which is the span name, not this field.

## Wire shapes

### Trace Tab Summary
`TraceTabSummary` carries the counts the toolbar badges and the Traces tab rows show, one
sub-record per tab of the trace-detail overlay: `RequestSummary(method, path, statusCode)`,
`SpansSummary(count, totalDurationMs, errorCount)`, `QueriesSummary(count, totalDurationMs)` and
`LogsSummary(count, errorCount, warnCount)`.

### Bucket Counts
`BucketCounts(all, errors, slow)`. Every listing response carries it twice: `bucketCounts` for
the whole store and `filteredBucketCounts` for the traces matching this request's
type/operation filter. The second is null only when the request filtered nothing at all, which
a request naming no type does not, since it still gets the default view's filter. The pair is
what lets the bucket chips show `filtered / total`.

### HTTP Exchange
`HttpExchange(HttpRequest request, HttpResponse response)`, built from a `RequestCompletedEvent`
and carried on `TraceTree.httpExchange`. `HttpResponse` is `status` and `headers`. `HttpRequest`
holds three flat fields and three nested records:

| Field on the wire | Contents |
|---|---|
| `method`, `path` | Verb and request URI. |
| `query` | The query string, values masked. |
| `headers` | Request headers, values masked. |
| `body.content`, `body.truncated` | Reserved. |
| `controller.class`, `controller.method` | The resolved `HandlerMethod`. |
| `params.query`, `params.form` | Parsed parameters, values masked. |
| `params.upload` | Reserved. |

`controller.class` is `HttpRequest.Controller.className` in Java, renamed on the wire by
`@JsonProperty("class")`, since `class` is a Java keyword.

The reserved fields are never populated: `RequestCaptureFilter` passes `null`, `false` and
`List.of()` for them on every request. They are the seam a body-capture implementation would
fill; see *Servlet Filters* in [`ARCHITECTURE.md`](ARCHITECTURE.md) for what such a pass has to
solve first.

### Trace Log and Query Info
`TraceLog(spanId, timestamp, level, loggerName, message, threadName)` is one captured log event,
correlated by the trace and span ids frozen in its MDC. It appears twice on the detail response:
once in `TraceTree.logs` and again on the `SpanNode` that emitted it. A log with no span id
appears in the flat list only.

`QueryInfo(spanId, sql, dbSystem, durationMs, timestamp, rowCount, creationOrder)` is one query
span. `sql` is masked and may be null when the instrumentation recorded no statement; the query
is still listed. `TraceTree.queries` is populated on the single-trace endpoint only, never on
the listing.

## The word "insights"

The word carries two unrelated features in the URL space, plus a prose shorthand.

- **The Insights tab** is the aggregated-metric charts: `dashboard/tabs/insights.js`, gated on
  the `insights` feature flag and backed by `InsightsController` under the **prefix**
  `/peekaboot/api/insights/{config,data,stream}`.
- **The insights suffix** marks the enriched read models: `/peekaboot/api/actuator/all/insights`,
  `/peekaboot/api/traces/insights` and `/peekaboot/api/traces/{traceId}/insights`. Nothing to do
  with the tab. `ActuatorInsightsResponse` and `TraceInsightsResponse` are their payloads.
- **Insights Data** in prose means the enriched shape itself: `TraceTree` and `SpanNode` rather
  than `TraceData` and `SpanData`.

So `/api/insights/**` and `/api/*/insights` are two different features that happen to share a
word. A path with `insights` in the middle is the tab's; a path ending in `insights` is a read
model.

### Backend-for-Frontend (BFF)
The pattern behind the suffix. The backend assembles, correlates and masks before the frontend
sees anything: logs already attached to their spans, issues already detected, SQL already
masked, thresholds already resolved onto `/api/features`. The frontend renders what it is given
and keeps no analysis of its own. Peekaboot's UI is served from the same process it observes, so
there is no second service to hold that logic.

## UI surfaces

### Dev toolbar
The bar injected into HTML responses by `DevToolbarFilter`. `ToolbarShell` renders the server-side
markup and `ToolbarDataProvider` embeds the JSON blob `toolbar.js` reads before it calls home.
Two payload shapes: `ToolbarSummary` for a page that came out of a traced request, `IdleMode` for
one that did not.

### Dashboard
The standalone UI at `/peekaboot/`. "Dashboard" always names the whole thing, never one tab. Its
ten tab modules register in strip order: Overview, Insights, Lifecycle, Traces, Meters,
Environment, Flyway, Loggers, Config, Scheduled Tasks. Each exports `id`, `label` and `render`,
and optionally `isAvailable` to hide its own strip button.

### Overview Tab
The landing tab, and the consumer of most of `ActuatorInsightsResponse`: the insights stat tiles,
build/git/Spring/Java/OS info, the Machine card, JVM Defaults, the datasource cards beside it,
the memory and storage meters, and the health banner with its component grid. The Machine card
holds CPU count and model, total memory, max heap, the detected container runtime, and the
machine's non-local addresses with reverse-resolved hostnames. Distinct from the Insights tab.

### Trace Detail
The overlay over a single trace, `trace-detail/trace-detail.js`. It runs in a shadow root and is
loaded two ways: lazily by `toolbar.js` through a dynamic import, eagerly by the dashboard's
static import. Its four tabs are `request`, `spans`, `queries` and `logs`, the same four names
`TraceTabSummary` uses.
