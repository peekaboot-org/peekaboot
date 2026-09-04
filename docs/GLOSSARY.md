# Peekaboot Glossary

This glossary defines terms used in the Peekaboot codebase and documentation.

## Core Tracing Concepts

### Trace
One unit of work in this process — an HTTP request, a scheduled job run — recorded as one or more spans forming a tree structure. Each trace has a unique `traceId`. Peekaboot traces a single process; it does not stitch traces together across services.

**Usage:** `TraceData`, `TraceTree`, `traceId`

### Span
A single unit of work within a trace. Examples include handling an HTTP request, executing a database query, or calling an external service. Spans have a parent-child relationship forming the trace tree.

**Usage:** `SpanData`, `SpanNode`, `spanId`, `parentId`

### Root Span
The first span in a trace with no parent within the trace. The root span determines the trace's action type and operation name.

**Usage:** `rootSpan`, `findRootSpan()`

## Trace Classification

### Root Action Type
The category of work initiated by the root span. Used to classify and filter traces.
`detectRootActionType()` checks a fixed set of rules in priority order and returns the
first match — a span matching more than one row always gets the one checked first, not
the most specific-sounding one. See
[www.peekaboot.org/docs/concepts](https://www.peekaboot.org/docs/concepts/#root-action-type) for
the full priority table and the classification gotchas (`HTTP_REQUEST` occupying two
priorities, `SCHEDULED_JOB` keyed on the `code.function`/`code.namespace` tag pair
Spring's own scheduled-task observation sets rather than a name match, and the resulting
`INTERNAL` classification both for schedulers Spring doesn't instrument — Quartz, a raw
thread — and for a *direct* call to a `@Scheduled` method, which carries only `@Observed`'s
own `class`/`method` tags, not Spring's pair) instead of duplicating them here.

`CONNECTION_POOL` marks a trace whose root is datasource-micrometer's `connection` span
&mdash; a pooled connection acquired outside any traced work (HikariCP maintenance), which
means a span carrying no parent at all. A connection acquired while serving a request whose
root span Peekaboot skips &mdash; its own, the actuator's &mdash; is the only span stored
under that trace id and so *looks* like a root, but it happened inside traced work and is
not this type. It is the one type the listing endpoint leaves out of its default
view: a request naming no root action type gets every other type, so with no `type` in the
URL the Traces tab shows everything but these, and selecting the Connection Pool chip both
reveals them and puts `type=CONNECTION_POOL` in the shareable URL. A client that wants the
store as it stands asks for `rootActionType=*`.

**Usage:** `RootActionType` enum, `rootActionType` field, `detectRootActionType()`

### Root Operation
The root span's raw name (`rootSpanData.name()`), exactly as the instrumentation that
created the span wrote it &mdash; not reformatted by Peekaboot. This is the value used
for filtering traces by a specific operation.

**Examples:**
- HTTP: `"http get /orders"` or `"http get /api/orders/{id}/report"`
- Scheduled (direct call to the `@Observed` method): `"order.reconcile.job"`
- Scheduled (fired by Spring's scheduler): `"task orderReconciler.reconcileOrders"`

**Usage:** `rootOperation` field, derived from `rootSpan.name()`

### Target (Scheduled Tasks)
In the Scheduled Tasks tab, "target" refers to the fully qualified method name of the
scheduled task (e.g., `net.example.TaskService.processItems`), read from Actuator's
`scheduledtasks` endpoint.

**Relationship:** A scheduled task's `target` does **not** equal the trace's
`rootOperation` outright: `target` is the fully-qualified method name, while
`rootOperation` is the root span's own name (see above) &mdash; typically
`task <bean>.<method>` when Spring's scheduler fired it. `TraceInsightsService`'s
`matchesRootOperation` bridges that mismatch with a `Class.method` suffix fallback (the
last two dot-segments of `target` matched against `rootOperation`), which is what lets
the Scheduled Tasks tab's "View traces" link (`dashboard/tabs/scheduled-tasks.js`) filter
correctly to a task's own runs; see `DashboardTabsIT.schedulerTracesLinkArrivesFiltered`
for the end-to-end proof, and
[www.peekaboot.org/docs/concepts](https://www.peekaboot.org/docs/concepts/) for the trace-side
vocabulary.

## Data Representations

### Trace Data (internal)
The trace as captured from OpenTelemetry and held in `TraceStore`: every span with its full
tag map, plus the logs and request metadata correlated to it. Internal only — no endpoint
serves it as-is; the insights endpoints are built from it.

**Usage:** `TraceData`, `SpanData`, `TraceDataBundle`

### Insights Data
Enriched and analyzed trace data suitable for UI display. Includes detected issues, hierarchical structure, and correlated logs.

**Usage:** `TraceTree`, `SpanNode`, `/api/traces/insights` endpoints

## Trace Analysis

### Trace Status
Overall health status of a trace.

| Value | Description |
|-------|-------------|
| `OK` | No issues detected |
| `HAS_ERRORS` | At least one span carries an error class or message; an error-level log alone does not — that only puts the trace in the Errors bucket |

**Usage:** `TraceStatus` enum, `status` field

### Issue
A detected problem or concern within a span, identified by analysis.

**Usage:** `SpanIssue` record, `issues` list

### Issue Type
Category of detected issue: `SLOW`, `VERY_SLOW`, `ERROR`, `SLOW_QUERY`, and
`HIGH_QUERY_COUNT`. See
[www.peekaboot.org/docs/concepts](https://www.peekaboot.org/docs/concepts/#issues) for what each
one fires on, and [www.peekaboot.org/docs/configuration](https://www.peekaboot.org/docs/configuration/)
for the current default thresholds and their property names.

**Usage:** `IssueType` enum

## Summary

### Trace Tab Summary
Per-trace statistics organized by UI tab categories (request, spans, queries, logs). Contains all information needed to render the dev toolbar and trace list.

| Field | Description |
|-------|-------------|
| `request` | HTTP request summary (method, path, statusCode) |
| `spans.count` | Number of spans in the trace |
| `spans.totalDurationMs` | Total span duration |
| `spans.errorCount` | Number of errored spans |
| `queries.count` | Number of database queries |
| `queries.totalDurationMs` | Total time spent in database |
| `logs.count` | Number of log entries |
| `logs.errorCount` | Error-level logs |
| `logs.warnCount` | Warning-level logs |

**Usage:** `TraceTabSummary` record, `summary` field on `TraceTree`

### Bucket Counts
How many traces each store bucket holds, carried twice on every list response: `bucketCounts` for the whole store and `filteredBucketCounts` for the traces matching the request's root-action/operation filter (null when the request carried no filter), so the Traces tab's bucket chips can show `filtered / total` while a filter is active.

| Field | Description |
|-------|-------------|
| `all` | Traces in the All bucket |
| `errors` | Traces in the Errors bucket — an errored span or an error-level log anywhere in the trace |
| `slow` | Traces in the Slow bucket — a whole-trace duration check against `slow-trace-threshold-ms`, **not** the same as a `SLOW`/`VERY_SLOW` issue on a span; see [www.peekaboot.org/docs/tracing](https://www.peekaboot.org/docs/tracing/) |

**Usage:** `BucketCounts` record, `bucketCounts`/`filteredBucketCounts` fields on `TraceInsightsResponse`

## Additional Data

### Trace Log
A log message captured during trace execution, correlated by `spanId`.

**Usage:** `TraceLog` record, `logs` field (on `TraceTree` and `SpanNode`)

### Query Info
Information about a database query executed within a trace.

**Usage:** `QueryInfo` record, `queries` field

### HTTP Exchange
HTTP request/response metadata captured for web traces. Contains nested `HttpRequest` and `HttpResponse` structures.

**HttpRequest fields:**
| Field | Description |
|-------|-------------|
| `method` | HTTP method (GET, POST, etc.) |
| `path` | Request path |
| `query` | Raw query string |
| `headers` | HTTP request headers |
| `body.content` | Request body content |
| `body.truncated` | Whether body was truncated |
| `controller.class` | Spring controller class |
| `controller.method` | Handler method name |
| `params.query` | URL query parameters |
| `params.form` | Form parameters |
| `params.upload` | Uploaded files |

`body.content`, `body.truncated` and `params.upload` are reserved fields: `RequestCaptureFilter`
never populates them, so they are always null/empty. They are the seam a body-capture
implementation would fill &mdash; see *Servlet Filters* in
[`ARCHITECTURE.md`](ARCHITECTURE.md) for what such a pass has to solve first.

**HttpResponse fields:**
| Field | Description |
|-------|-------------|
| `status` | HTTP status code |
| `headers` | HTTP response headers |

**Usage:** `HttpExchange` record, `httpExchange` field on `TraceTree`

## UI Components

### Dev toolbar
Development-time toolbar injected into HTML responses, showing trace data inline with the rendered page.

### Dashboard
Standalone web UI at `/peekaboot/` showing application health, traces, and diagnostics, organized into tabs (Overview, Insights, Lifecycle, Traces, Meters, Environment, Flyway, Loggers, Config, Scheduled Tasks). "Dashboard" always names the whole UI, never one tab.

### Overview Tab
The dashboard's landing tab: build/git/Spring/Java/OS/JVM info, a Machine card (CPU count and model, total memory, max heap, the detected container runtime — docker, podman, kubernetes, a generic container, or none — and the machine's non-local addresses in an IPv4/IPv6 tab strip with their reverse-resolved hostnames), the insights stat tiles, datasource cards (seated beside JVM Defaults), memory/storage usage meters, and the health banner. Not to be confused with the Insights tab below.

### Insights
Ambiguous outside context, so used narrowly here: as a tab name, "Insights" is the aggregated-metric-charts feature (`dashboard/tabs/insights.js`, backed by `/api/insights/*`). "Insights API"/"Insights Data" (see above) is the unrelated BFF pattern used throughout the backend to enrich raw trace data before it reaches the frontend.

### Trace Detail
Detailed view of a single trace showing the full span tree with timing visualization.

## API Patterns

### Backend-for-Frontend (BFF)
The insights API enriches raw data before sending to the frontend, keeping frontend logic simple. The frontend receives pre-processed data (e.g., logs already attached to spans, issues already detected).

**Principle:** Complex data transformations happen in the backend; frontend renders what it receives.