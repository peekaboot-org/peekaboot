# Peekaboot Glossary

This glossary defines terms used in the Peekaboot codebase and documentation.

## Core Tracing Concepts

### Trace
A distributed trace representing a complete request flow through the system. A trace consists of one or more spans forming a tree structure. Each trace has a unique `traceId`.

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
[peekaboot.org/docs/concepts](https://peekaboot.org/docs/concepts/#root-action-type) for
the full priority table and the classification gotchas (`HTTP_REQUEST` occupying two
priorities, `SCHEDULED_JOB` keyed on the `code.function`/`code.namespace` tag pair
Spring's own scheduled-task observation sets rather than a name match, and the resulting
`INTERNAL` classification both for schedulers Spring doesn't instrument — Quartz, a raw
thread — and for a *direct* call to a `@Scheduled` method, which carries only `@Observed`'s
own `class`/`method` tags, not Spring's pair) instead of duplicating them here.

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
correctly to a task's own runs; see `DashboardTabsTest.schedulerTracesLinkArrivesFiltered`
for the end-to-end proof, and
[peekaboot.org/docs/concepts](https://peekaboot.org/docs/concepts/) for the trace-side
vocabulary.

## Data Representations

### Raw Data
The unprocessed trace data as captured from OpenTelemetry. Contains all spans with their full attributes.

**Usage:** `TraceData`, `SpanData`, `/api/traces/raw` endpoints

### Insights Data
Enriched and analyzed trace data suitable for UI display. Includes detected issues, hierarchical structure, and correlated logs.

**Usage:** `TraceTree`, `SpanNode`, `/api/traces/insights` endpoints

## Trace Analysis

### Trace Status
Overall health status of a trace.

| Value | Description |
|-------|-------------|
| `OK` | No issues detected |
| `HAS_ERRORS` | One or more spans have errors |

**Usage:** `TraceStatus` enum, `status` field

### Issue
A detected problem or concern within a span, identified by analysis.

**Usage:** `SpanIssue` record, `issues` list

### Issue Type
Category of detected issue: `SLOW`, `VERY_SLOW`, `ERROR`, `SLOW_QUERY`, and
`HIGH_QUERY_COUNT`. See
[peekaboot.org/docs/concepts](https://peekaboot.org/docs/concepts/#issues) for what each
one fires on, and [peekaboot.org/docs/configuration](https://peekaboot.org/docs/configuration/)
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

### Trace List Summary
Aggregate statistics across multiple traces in list responses.

| Field | Description |
|-------|-------------|
| `traceCount` | Number of traces |
| `errorCount` | Traces with `TraceStatus.HAS_ERRORS` |
| `slowCount` | Traces with a `SLOW`/`VERY_SLOW` issue on any span — **not** the same as the Slow bucket, which is a whole-trace duration check against `slow-trace-threshold-ms`; see [peekaboot.org/docs/tracing](https://peekaboot.org/docs/tracing/) |
| `avgDurationMs` | Average trace duration |

**Usage:** `TraceListSummary` record, `summary` field on `TraceInsightsResponse`

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

**HttpResponse fields:**
| Field | Description |
|-------|-------------|
| `status` | HTTP status code |
| `headers` | HTTP response headers |

**Usage:** `HttpExchange` record, `httpExchange` field on `TraceTree` and `TraceRawData`

## UI Components

### Debug Toolbar
Development-time toolbar injected into HTML responses, showing trace data inline with the rendered page.

### Dashboard
Standalone web UI at `/peekaboot/` showing application health, traces, and diagnostics.

### Trace Detail
Detailed view of a single trace showing the full span tree with timing visualization.

## API Patterns

### Backend-for-Frontend (BFF)
The insights API enriches raw data before sending to the frontend, keeping frontend logic simple. The frontend receives pre-processed data (e.g., logs already attached to spans, issues already detected).

**Principle:** Complex data transformations happen in the backend; frontend renders what it receives.