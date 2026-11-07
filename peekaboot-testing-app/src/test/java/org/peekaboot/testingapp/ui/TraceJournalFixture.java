package org.peekaboot.testingapp.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A trace snapshot for the application to find when it boots: one earlier run, which stopped while a
 * request was still running, so its only stored span has no root. The lines are written as text in
 * the format {@code JournalCodecTest} pins, the way an earlier Peekaboot left them on disk; a change
 * to that format fails this fixture too, which is what a user's next start would meet. Like every
 * snapshot it ends with a line per bucket, Errors and Slow first; the span failed nothing and ran
 * fast, so only All lists the trace. A second trace of the same shape is in no bucket line, which no
 * fold would write. A third holds one log line and no span, so unlike the first it lists a row of its
 * own. A restore keeps exactly the traces the bucket lines list, so the second trace stays out unless
 * a bucket line goes unread.
 */
final class TraceJournalFixture {

    static final String EARLIER_RUN = "2026-10-30T08:00:00Z";

    private TraceJournalFixture() {}

    static void writeInterruptedTrace(Path dir, String traceId, String unlistedTraceId, String loggedTraceId) {
        String snapshot = String.join(
                        "\n",
                        "{\"type\":\"snapshot\",\"version\":1,\"absorbedJournal\":null}",
                        "{\"type\":\"run\",\"startedAt\":\"" + EARLIER_RUN + "\"}",
                        querySpanLine(traceId, 1),
                        querySpanLine(unlistedTraceId, 2),
                        logLine(loggedTraceId),
                        "{\"type\":\"bucket\",\"bucket\":\"ERRORS\",\"traceIds\":[]}",
                        "{\"type\":\"bucket\",\"bucket\":\"SLOW\",\"traceIds\":[]}",
                        "{\"type\":\"bucket\",\"bucket\":\"ALL\",\"traceIds\":[\"" + traceId + "\",\"" + loggedTraceId
                                + "\"]}")
                + "\n";
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("traces.snapshot.jsonl"), snapshot, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to seed " + dir, e);
        }
    }

    private static String logLine(String traceId) {
        return "{\"type\":\"log\",\"traceId\":\"" + traceId + "\",\"spanId\":null,"
                + "\"timestamp\":\"2026-10-30T08:00:01.001Z\",\"level\":\"INFO\","
                + "\"logger\":\"org.peekaboot.testingapp.PersonService\",\"thread\":\"http-nio-1\","
                + "\"message\":\"looking up the person\",\"stackTrace\":null}";
    }

    private static String querySpanLine(String traceId, int creationOrder) {
        return "{\"type\":\"span\",\"span\":{\"traceId\":\"" + traceId + "\",\"spanId\":\"query\","
                + "\"parentId\":\"handler\",\"remoteParent\":false,\"name\":\"SELECT person\","
                + "\"kind\":\"CLIENT\",\"startTime\":\"2026-10-30T08:00:01Z\","
                + "\"endTime\":\"2026-10-30T08:00:01.002Z\",\"tags\":{},\"events\":[],"
                + "\"exception\":null,\"creationOrder\":" + creationOrder + "}}";
    }
}
