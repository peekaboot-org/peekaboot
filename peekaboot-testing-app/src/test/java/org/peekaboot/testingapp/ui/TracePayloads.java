package org.peekaboot.testingapp.ui;

import io.micrometer.tracing.Span;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.SpanEvent;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SpanStatus;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.domain.trace.TraceDetail;
import org.peekaboot.backend.domain.trace.TraceIssue;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;

/**
 * Trace payloads for a module rendered straight in the page, built from the records the API
 * serves and written by its mapper. A component renamed on the backend then reaches the
 * frontend under its new name, and the test that renders it fails.
 *
 * <p>Every component starts at a neutral value, so a test names only what it asserts on.
 */
final class TracePayloads {

    private static final String TRACE_ID = "trace";

    private TracePayloads() {}

    /** An OK span named after its id that starts at the epoch, took no time and carries nothing. */
    static SpanPayload span(String spanId) {
        return new SpanPayload(spanId);
    }

    /**
     * A trace of no duration that starts at the epoch: complete and OK around {@code rootOrNull},
     * still in progress where that is null.
     */
    static TracePayload trace(SpanPayload rootOrNull) {
        return new TracePayload(rootOrNull);
    }

    /** An INFO line written on {@code spanId} at the epoch, without a throwable. */
    static TraceLog log(String spanId, String message) {
        return new TraceLog(
                TRACE_ID,
                spanId,
                Instant.EPOCH,
                "INFO",
                "org.peekaboot.Fixture",
                "main",
                message,
                null,
                List.of(),
                List.of());
    }

    static final class SpanPayload {

        private final String spanId;
        private String name;
        private Span.Kind kind;
        private Instant startTime = Instant.EPOCH;
        private long durationMs;
        private long selfMs;
        private SpanStatus status = SpanStatus.OK;
        private List<SpanPayload> children = List.of();
        private final Map<String, String> tags = new LinkedHashMap<>();
        private final List<SpanEvent> events = new ArrayList<>();
        private List<TraceIssue> issues = List.of();
        private SpanExceptionInfo exception;
        private SqlStatement statement;
        private Long rowCount;
        private boolean asyncEntry;

        private SpanPayload(String spanId) {
            this.spanId = spanId;
            this.name = spanId;
        }

        SpanPayload name(String name) {
            this.name = name;
            return this;
        }

        SpanPayload kind(Span.Kind kind) {
            this.kind = kind;
            return this;
        }

        SpanPayload startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        SpanPayload durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        SpanPayload selfMs(long selfMs) {
            this.selfMs = selfMs;
            return this;
        }

        SpanPayload status(SpanStatus status) {
            this.status = status;
            return this;
        }

        SpanPayload children(SpanPayload... children) {
            this.children = List.of(children);
            return this;
        }

        /** Tags keep the order they are added in, so a test of the tab's own ordering can hand them over unsorted. */
        SpanPayload tag(String key, String value) {
            tags.put(key, value);
            return this;
        }

        SpanPayload event(String name, Instant timestamp) {
            events.add(new SpanEvent(name, timestamp));
            return this;
        }

        SpanPayload issues(TraceIssue... issues) {
            this.issues = List.of(issues);
            return this;
        }

        SpanPayload exception(SpanExceptionInfo exception) {
            this.exception = exception;
            return this;
        }

        SpanPayload statement(SqlStatement statement) {
            this.statement = statement;
            return this;
        }

        SpanPayload rowCount(long rowCount) {
            this.rowCount = rowCount;
            return this;
        }

        SpanPayload asyncEntry() {
            this.asyncEntry = true;
            return this;
        }

        private SpanNode node() {
            return new SpanNode(
                    spanId,
                    name,
                    kind,
                    startTime,
                    durationMs,
                    selfMs,
                    status,
                    nodes(children),
                    tags,
                    events,
                    issues,
                    exception,
                    statement,
                    rowCount,
                    asyncEntry);
        }
    }

    static final class TracePayload {

        private final SpanPayload root;
        private Instant startTime = Instant.EPOCH;
        private long durationMs;
        private List<TraceSummary.Hotspot> hotspots = List.of();
        private List<SpanPayload> orphans = List.of();
        private List<TraceLog> logs = List.of();

        private TracePayload(SpanPayload root) {
            this.root = root;
        }

        TracePayload startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        TracePayload durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        TracePayload hotspots(TraceSummary.Hotspot... hotspots) {
            this.hotspots = List.of(hotspots);
            return this;
        }

        TracePayload orphans(SpanPayload... orphans) {
            this.orphans = List.of(orphans);
            return this;
        }

        TracePayload logs(TraceLog... logs) {
            this.logs = List.of(logs);
            return this;
        }

        /** The trace as {@code GET /api/traces/{id}} writes it. */
        String json() {
            return PeekabootJson.MAPPER.writeValueAsString(new TraceResource(summary(), detail()));
        }

        private TraceSummary summary() {
            return new TraceSummary(
                    TRACE_ID,
                    startTime,
                    durationMs,
                    root != null,
                    null,
                    RootActionType.UNKNOWN,
                    null,
                    null,
                    root != null ? TraceOutcome.OK : TraceOutcome.IN_PROGRESS,
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    hotspots,
                    new TraceSummary.Counts(
                            0, new TraceSummary.QueryCounts(0, 0), new TraceSummary.LogCounts(0, 0, 0), 0, 0),
                    null);
        }

        private TraceDetail detail() {
            return new TraceDetail(root == null ? null : root.node(), nodes(orphans), logs, List.of(), null);
        }
    }

    private static List<SpanNode> nodes(List<SpanPayload> spans) {
        return spans.stream().map(SpanPayload::node).toList();
    }
}
