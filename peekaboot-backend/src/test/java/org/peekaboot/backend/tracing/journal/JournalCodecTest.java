package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceRemnant;

class JournalCodecTest {

    private static final Instant AT = Instant.parse("2026-10-31T08:00:00Z");

    /** Every field a span can carry set, one event stamped to the nanosecond. */
    private static final SpanData RICH_SPAN = span("s1")
            .in("t1")
            .parent("p1")
            .named("GET /persons")
            .kind(Span.Kind.SERVER)
            .at(AT, Duration.ofMillis(12))
            .tag("http.route", "/persons")
            .events(List.of(new SpanData.Event("retry", AT.plusNanos(1_234_567))))
            .exception("IllegalStateException", "boom", "java.lang.IllegalStateException: boom")
            .order(3)
            .build();

    private static final LogCapturedEvent RICH_LOG = log("t1")
            .inSpan("s1")
            .timestamp(AT.plusMillis(5))
            .at("ERROR")
            .from("com.acme.Orders")
            .onThread("http-nio-1")
            .saying("boom")
            .withStackTrace("java.lang.IllegalStateException: boom\n\tat com.acme.Orders.list(Orders.java:12)")
            .build();

    /** The format on disk, pinned: a change here is a new {@link JournalCodec#VERSION}. */
    @Test
    void writesEachLineInTheFormatOnDisk() {
        RequestCompletedEvent request = new RequestCompletedEvent(
                "t1", "GET", "/persons", null, Map.of(), "PersonController", "list", Map.of(), Map.of(), 200, Map.of());

        assertThat(JournalCodec.encode(new JournalLine.SnapshotHeader(1, "j1")))
                .isEqualTo("{\"type\":\"snapshot\",\"version\":1,\"absorbedJournal\":\"j1\"}");
        assertThat(JournalCodec.encode(new JournalLine.JournalHeader(1, "j1")))
                .isEqualTo("{\"type\":\"journal\",\"version\":1,\"id\":\"j1\"}");
        assertThat(JournalCodec.encode(new JournalLine.RunLine(AT)))
                .isEqualTo("{\"type\":\"run\",\"startedAt\":\"2026-10-31T08:00:00Z\"}");
        assertThat(JournalCodec.encode(new JournalLine.RunLine(null)))
                .isEqualTo("{\"type\":\"run\",\"startedAt\":null}");
        assertThat(JournalCodec.encode(new JournalLine.SpanLine(RICH_SPAN)))
                .isEqualTo("{\"type\":\"span\",\"span\":{\"traceId\":\"t1\",\"spanId\":\"s1\",\"parentId\":\"p1\","
                        + "\"remoteParent\":false,\"name\":\"GET /persons\",\"kind\":\"SERVER\","
                        + "\"startTime\":\"2026-10-31T08:00:00Z\",\"endTime\":\"2026-10-31T08:00:00.012Z\","
                        + "\"tags\":{\"http.route\":\"/persons\"},"
                        + "\"events\":[{\"name\":\"retry\",\"timestamp\":\"2026-10-31T08:00:00.001234567Z\"}],"
                        + "\"exception\":{\"type\":\"IllegalStateException\",\"message\":\"boom\","
                        + "\"stackTrace\":\"java.lang.IllegalStateException: boom\"},\"creationOrder\":3}}");
        assertThat(JournalCodec.encode(JournalLine.LogLine.of(RICH_LOG)))
                .isEqualTo(
                        "{\"type\":\"log\",\"traceId\":\"t1\",\"spanId\":\"s1\",\"timestamp\":\"2026-10-31T08:00:00.005Z\","
                                + "\"level\":\"ERROR\",\"logger\":\"com.acme.Orders\",\"thread\":\"http-nio-1\","
                                + "\"message\":\"boom\",\"stackTrace\":\"java.lang.IllegalStateException: boom\\n\\tat "
                                + "com.acme.Orders.list(Orders.java:12)\"}");
        assertThat(JournalCodec.encode(new JournalLine.RequestLine(request)))
                .isEqualTo(
                        "{\"type\":\"request\",\"request\":{\"traceId\":\"t1\",\"method\":\"GET\",\"path\":\"/persons\","
                                + "\"queryString\":null,\"requestHeaders\":{},\"controllerClass\":\"PersonController\","
                                + "\"controllerMethod\":\"list\",\"queryParams\":{},\"formParams\":{},\"status\":200,"
                                + "\"responseHeaders\":{}}}");
        assertThat(JournalCodec.encode(new JournalLine.DiscardLine("t1")))
                .isEqualTo("{\"type\":\"discard\",\"traceId\":\"t1\"}");
    }

    /** Every input reads back as what was written; a replay through the store's methods sees the same events. */
    @Test
    void eachInputReadsBackAsWritten() {
        TraceInput[] inputs = {
            new TraceInput.SpanArrived(
                    span("s1").in("t1").tag("k", "v").order(3).build()),
            new TraceInput.SpanArrived(RICH_SPAN),
            new TraceInput.LogCaptured(log("t1").saying("hello").build()),
            new TraceInput.LogCaptured(RICH_LOG),
            new TraceInput.LogCaptured(untraced().saying("startup").build()),
            new TraceInput.RequestCompleted(new RequestCompletedEvent(
                    "t1",
                    "POST",
                    "/orders",
                    "a=1",
                    Map.of("accept", "*/*"),
                    "C",
                    "m",
                    Map.of("a", List.of("1")),
                    Map.of(),
                    201,
                    Map.of("location", "/orders/1"))),
            new TraceInput.TraceDiscarded("t1"),
            new TraceInput.RunStarted(AT)
        };

        for (TraceInput input : inputs) {
            JournalLine line = JournalCodec.lineOf(input);
            assertThat(JournalCodec.decode(JournalCodec.encode(line)))
                    .as("%s", input)
                    .isEqualTo(line);
        }
        JournalLine.LogLine untracedLine =
                (JournalLine.LogLine) JournalCodec.decode(JournalCodec.encode(JournalCodec.lineOf(inputs[4])));
        assertThat(untracedLine.traceId()).isNull();
        assertThat(untracedLine.toEvent()).isEqualTo(((TraceInput.LogCaptured) inputs[4]).log());
    }

    /** A fold's remnant and bucket lines, pinned like the input lines. */
    @Test
    void writesTheFoldsLinesInTheFormatOnDisk() {
        TraceRemnant remnant =
                new TraceRemnant(2, 3, true, true, AT, AT.plusMillis(31), AT, AT.plusMillis(10), List.of("s1", "s2"));
        JournalLine remnantLine = new JournalLine.RemnantLine("t1", remnant);
        JournalLine bucketLine = new JournalLine.BucketLine(TraceBucket.SLOW, List.of("t2", "t1"));

        assertThat(JournalCodec.encode(remnantLine))
                .isEqualTo("{\"type\":\"remnant\",\"traceId\":\"t1\",\"remnant\":{\"droppedSpans\":2,\"droppedLogs\":3,"
                        + "\"parentlessSpanStored\":true,\"failed\":true,\"start\":\"2026-10-31T08:00:00Z\","
                        + "\"end\":\"2026-10-31T08:00:00.031Z\",\"evictedSyncStart\":\"2026-10-31T08:00:00Z\","
                        + "\"evictedSyncEnd\":\"2026-10-31T08:00:00.010Z\",\"asyncSpanIds\":[\"s1\",\"s2\"]}}");
        assertThat(JournalCodec.encode(bucketLine))
                .isEqualTo("{\"type\":\"bucket\",\"bucket\":\"SLOW\",\"traceIds\":[\"t2\",\"t1\"]}");
        assertThat(JournalCodec.decode(JournalCodec.encode(remnantLine))).isEqualTo(remnantLine);
        assertThat(JournalCodec.decode(JournalCodec.encode(bucketLine))).isEqualTo(bucketLine);
    }

    /** A line written outside any trace is an ordinary log line whose trace id is null. */
    @Test
    void anUntracedLineIsALogLineWithoutATrace() {
        JournalLine line = JournalCodec.lineOf(
                new TraceInput.LogCaptured(untraced().saying("startup").build()));

        assertThat(line).isInstanceOf(JournalLine.LogLine.class);
        assertThat(line.traceId()).isNull();
    }

    /** What the reader cannot use: garbage, an empty line, a cut-off line, an unknown type, and lines missing what their type needs. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "",
                "{\"type\":\"run\",\"startedAt\":\"2026-10-31T08:0",
                "{\"type\":\"teleport\"}",
                "{\"type\":\"span\"}",
                "{\"type\":\"span\",\"span\":{\"spanId\":\"s1\",\"parentId\":null,\"remoteParent\":false,\"name\":\"op\","
                        + "\"kind\":null,\"startTime\":null,\"endTime\":null,\"tags\":{},\"events\":[],"
                        + "\"exception\":null,\"creationOrder\":1}}",
                "{\"type\":\"span\",\"span\":{\"traceId\":\"t1\",\"spanId\":\"s1\",\"parentId\":null,\"remoteParent\":false,"
                        + "\"kind\":null,\"startTime\":null,\"endTime\":null,\"tags\":{},\"events\":[],"
                        + "\"exception\":null,\"creationOrder\":1}}",
                "{\"type\":\"log\",\"level\":\"INFO\",\"logger\":\"x\",\"thread\":\"main\"}",
                "{\"type\":\"log\",\"timestamp\":\"2026-10-31T08:00:00Z\",\"level\":\"INFO\",\"logger\":\"x\","
                        + "\"thread\":\"main\"}",
                "{\"type\":\"request\",\"request\":{\"method\":\"GET\",\"status\":200}}",
                "{\"type\":\"discard\"}",
                "{\"type\":\"remnant\",\"traceId\":\"t1\"}",
                "{\"type\":\"remnant\",\"remnant\":{\"droppedSpans\":2,\"droppedLogs\":3,\"parentlessSpanStored\":true,"
                        + "\"failed\":true,\"start\":null,\"end\":null,\"evictedSyncStart\":null,\"evictedSyncEnd\":null,"
                        + "\"asyncSpanIds\":[]}}",
                "{\"type\":\"remnant\",\"traceId\":\"t1\",\"remnant\":{\"droppedSpans\":2,\"droppedLogs\":3,"
                        + "\"parentlessSpanStored\":true,\"failed\":true,\"start\":null,\"end\":null,"
                        + "\"evictedSyncStart\":null,\"evictedSyncEnd\":null}}",
                "{\"type\":\"bucket\",\"traceIds\":[\"t1\"]}",
                "{\"type\":\"bucket\",\"bucket\":\"SLOW\"}",
                "{\"type\":\"journal\",\"version\":1}"
            })
    void readsNothingFromALineItCannotUse(String text) {
        assertThat(JournalCodec.decode(text)).isNull();
    }
}
