package org.peekaboot.backend.tracing.digest;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Every failed trace of one failure: the cause's class, the route and the top application frame
 * without its line number. {@code errors.jsonl} holds one per line, so a compact constructor that finds
 * a required field missing refuses the line.
 *
 * @param type the cause's exception class; null for a 5xx or a failure that named none
 * @param route the route of the trace's root request; null where the root is no HTTP request
 * @param applicationFrame the cause's top application frame, its line number dropped, so an edit that
 *     moves the line keeps the entry; null where the cause names none
 * @param count how many failed traces the entry folds, at least one
 * @param firstSeen when the earliest of the traces started
 * @param lastSeen when the latest of the traces started
 * @param lastMessage the latest trace's cause message, masked as its summary masks it
 * @param lastTraceId the latest trace
 * @param runs when each run the failure occurred in started, oldest first, at most {@link ErrorDigest#MAX_RUNS};
 *     a trace from a run that had not reported ready names none
 */
public record DigestedError(
        @Nullable String type,
        @Nullable String route,
        @Nullable String applicationFrame,
        int count,
        Instant firstSeen,
        Instant lastSeen,
        @Nullable String lastMessage,
        String lastTraceId,
        List<Instant> runs) {

    public DigestedError {
        requireNonNull(firstSeen, "firstSeen");
        requireNonNull(lastSeen, "lastSeen");
        requireNonNull(lastTraceId, "lastTraceId");
        runs = List.copyOf(requireNonNull(runs, "runs"));
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
    }

    /** A failure's first trace. */
    static DigestedError first(Key key, Instant seen, @Nullable String message, String traceId, @Nullable Instant run) {
        return new DigestedError(
                key.type(),
                key.route(),
                key.applicationFrame(),
                1,
                seen,
                seen,
                message,
                traceId,
                run == null ? List.of() : List.of(run));
    }

    /** This failure with one more trace. A trace that started before the latest one counts without becoming it. */
    DigestedError seenAgain(Instant seen, @Nullable String message, String traceId, @Nullable Instant run) {
        boolean latest = !seen.isBefore(lastSeen);
        return new DigestedError(
                type,
                route,
                applicationFrame,
                count + 1,
                seen.isBefore(firstSeen) ? seen : firstSeen,
                latest ? seen : lastSeen,
                latest ? message : lastMessage,
                latest ? traceId : lastTraceId,
                withRun(run));
    }

    private List<Instant> withRun(@Nullable Instant run) {
        if (run == null || runs.contains(run)) {
            return runs;
        }
        List<Instant> more = new ArrayList<>(runs);
        more.add(run);
        return more.size() > ErrorDigest.MAX_RUNS
                ? more.subList(more.size() - ErrorDigest.MAX_RUNS, more.size())
                : more;
    }

    Key key() {
        return Key.of(type, route, applicationFrame);
    }

    /** What makes two failed traces one failure. */
    record Key(
            @Nullable String type,
            @Nullable String route,
            @Nullable String applicationFrame) {

        /**
         * The frame without its line number: {@code a.B.c(B.java:42)} becomes {@code a.B.c(B.java)}. Logback's
         * packaging data after the frame, such as {@code ~[classes/:na]}, stays.
         */
        static Key of(@Nullable String type, @Nullable String route, @Nullable String applicationFrame) {
            return new Key(
                    type,
                    route,
                    applicationFrame == null ? null : applicationFrame.replaceFirst(":\\d+\\)(?=[^()]*$)", ")"));
        }
    }
}
