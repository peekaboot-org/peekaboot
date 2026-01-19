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

| Value | Description | Detection |
|-------|-------------|-----------|
| `HTTP_REQUEST` | Web request handler | SERVER kind + http.* tags |
| `SCHEDULED_JOB` | Scheduled/cron task | Name contains "schedule", "cron", "timer", "job" |
| `MESSAGE_CONSUMER` | Message queue consumer | CONSUMER kind or messaging.* tags |
| `RPC_CALL` | Remote procedure call | SERVER kind + rpc.* tags |
| `DATABASE` | Database operation | CLIENT kind + db.* tags |
| `INTERNAL` | Internal operation | null kind |
| `UNKNOWN` | Unclassified | Fallback |

**Usage:** `RootActionType` enum, `rootActionType` field, `detectRootActionType()`

### Root Operation
The name of the root span, typically identifying the specific endpoint, scheduled method, or operation being performed. This is the value used for filtering traces by a specific operation.

**Examples:**
- HTTP: `"GET /api/users"` or `"UserController.getUsers"`
- Scheduled: `"net.example.TaskService.processItems"`

**Usage:** `rootOperation` field, derived from `rootSpan.name()`

### Target (Scheduled Tasks)
In the Scheduled Tasks tab, "target" refers to the fully qualified method name of the scheduled task (e.g., `net.example.TaskService.processItems`). This corresponds to the `rootOperation` when filtering traces for that scheduled job.

**Relationship:** A scheduled task's `target` becomes the trace's `rootOperation` when that task executes.

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
| `HAS_SLOW_SPANS` | Contains spans exceeding thresholds |

**Usage:** `TraceStatus` enum, `status` field

### Issue
A detected problem or concern within a span, identified by analysis.

**Usage:** `SpanIssue` record, `issues` list

### Issue Type
Category of detected issue.

| Value | Description | Default Threshold |
|-------|-------------|-------------------|
| `SLOW` | Span exceeds slow threshold | 100ms |
| `VERY_SLOW` | Span exceeds very slow threshold | 500ms |
| `ERROR` | Span has error status | - |
| `SLOW_QUERY` | Database query too slow | 50ms |
| `HIGH_QUERY_COUNT` | Too many queries in span | 5 queries |

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
| `errorCount` | Traces with errors |
| `slowCount` | Traces with slow spans |
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
| `queryString` | Raw query string |
| `headers` | HTTP request headers |
| `body.content` | Request body content |
| `body.truncated` | Whether body was truncated |
| `controller.className` | Spring controller class |
| `controller.methodName` | Handler method name |
| `params.query` | URL query parameters |
| `params.form` | Form parameters |
| `params.files` | Uploaded files |

**HttpResponse fields:**
| Field | Description |
|-------|-------------|
| `statusCode` | HTTP status code |
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
