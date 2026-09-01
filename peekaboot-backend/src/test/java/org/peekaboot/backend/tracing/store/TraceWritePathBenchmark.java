package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Write-path benchmark harness for the deduplicating span cap: the cap counts real spans
 * only, so double-instrumented duplicates are folded away before it is checked.
 *
 * <p>Deduplicating inside {@link TraceDataBundle#addSpan} puts extra work on every
 * exported span's hot write path. This measures that cost directly rather than asserting a
 * number with nothing behind it: it replays the same large, N+1-shaped synthetic trace
 * through two implementations of the write path -
 *
 * <ul>
 *   <li>{@code timeOldPath} - a plain list append plus a trim once the cap is exceeded, no
 *       deduplication;</li>
 *   <li>{@code timeNewPath} - {@link TraceDataBundle#addSpan}, which folds duplicates away
 *       before the cap is ever checked.</li>
 * </ul>
 *
 * <p>Both run against the identical raw span sequence and the identical cap, isolating the
 * cost of the fold logic itself.
 *
 * <p>Not picked up by Surefire's default include patterns (<code>**&#47;*Test.java</code> etc.) -
 * wall-clock measurement has no place in the pristine, deterministic default test run. Run it
 * on demand:
 *
 * <pre>mvn -pl peekaboot-backend test -Dtest=TraceWritePathBenchmark</pre>
 *
 * <p>Numbers vary by machine and JIT warmup.
 */
class TraceWritePathBenchmark {

    /** 500 real queries -&gt; 1001 raw spans (each query doubled, plus the root): roughly a
     * 1000-span pre-dedup trace at the default cap. */
    private static final int REAL_QUERY_COUNT = 500;
    // Comfortably above REAL_QUERY_COUNT + 1 (the queries plus the root span) so the cap
    // itself never truncates this trace - the benchmark measures the fold-on-insertion
    // dedup cost, not cap-eviction cost, which is a separate, already-tested concern.
    private static final int CAP = 600;
    private static final int WARMUP_ITERATIONS = 20;
    private static final int MEASURED_ITERATIONS = 50;

    @Test
    void reportsWritePathThroughputBeforeAndAfterWriteTimeDeduplication() {
        List<SpanData> rawSpans = buildNPlusOneShapedTrace(REAL_QUERY_COUNT);
        assertThat(rawSpans).hasSize(REAL_QUERY_COUNT * 2 + 1);

        // Correctness sanity check alongside the timing: the new path must still fold every
        // duplicate away, leaving exactly the real spans (TraceDataBundleTest and
        // InMemoryTraceStoreTest cover this in depth; this is a cheap guard against the
        // benchmark silently drifting from what the fold logic actually does).
        TraceDataBundle sanityBundle = new TraceDataBundle("bench-sanity");
        rawSpans.forEach(span -> sanityBundle.addSpan(span, CAP));
        assertThat(sanityBundle.spans()).hasSize(REAL_QUERY_COUNT + 1);
        assertThat(sanityBundle.truncated()).isFalse();

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            timeOldPath(rawSpans, CAP);
            timeNewPath(rawSpans, CAP);
        }

        long oldTotalNanos = 0;
        long newTotalNanos = 0;
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            oldTotalNanos += timeOldPath(rawSpans, CAP);
            newTotalNanos += timeNewPath(rawSpans, CAP);
        }

        double oldAvgMs = (oldTotalNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;
        double newAvgMs = (newTotalNanos / (double) MEASURED_ITERATIONS) / 1_000_000.0;
        double oldNsPerSpan = oldTotalNanos / (double) (MEASURED_ITERATIONS * rawSpans.size());
        double newNsPerSpan = newTotalNanos / (double) (MEASURED_ITERATIONS * rawSpans.size());

        System.out.println("=== TraceWritePathBenchmark (" + rawSpans.size() + " raw spans/trace, "
                + MEASURED_ITERATIONS + " measured iterations, cap=" + CAP + ") ===");
        System.out.printf(
                "old path (append + trim, no dedup):        %.3f ms/trace, %.1f ns/span%n", oldAvgMs, oldNsPerSpan);
        System.out.printf(
                "new path (fold-on-insertion dedup):        %.3f ms/trace, %.1f ns/span%n", newAvgMs, newNsPerSpan);
        System.out.printf(
                "overhead added by write-time dedup:        %.3f ms/trace (%.2fx)%n",
                newAvgMs - oldAvgMs, newAvgMs / oldAvgMs);
    }

    private static long timeOldPath(List<SpanData> spans, int cap) {
        // Reconstructs pre-fix TraceDataBundle.addSpan: unconditional append, trim the
        // oldest entries once the cap is exceeded, no deduplication.
        List<SpanData> store = new ArrayList<>();
        long start = System.nanoTime();
        for (SpanData span : spans) {
            store.add(span);
            if (store.size() > cap) {
                store.subList(0, store.size() - cap).clear();
            }
        }
        long elapsed = System.nanoTime() - start;
        if (store.isEmpty()) {
            throw new AssertionError("unreachable - keeps the store live across the JIT's dead-code elimination");
        }
        return elapsed;
    }

    private static long timeNewPath(List<SpanData> spans, int cap) {
        TraceDataBundle bundle = new TraceDataBundle("bench-trace");
        long start = System.nanoTime();
        for (SpanData span : spans) {
            bundle.addSpan(span, cap);
        }
        long elapsed = System.nanoTime() - start;
        if (bundle.spans().isEmpty()) {
            throw new AssertionError("unreachable - keeps the bundle live across the JIT's dead-code elimination");
        }
        return elapsed;
    }

    /** One root span plus {@code realQueryCount} query spans, each immediately preceded in
     * the returned list by its double-instrumented duplicate - the arrival order the OTel
     * BatchSpanProcessor actually produces (see {@link TraceDataBundle}'s class Javadoc). */
    private static List<SpanData> buildNPlusOneShapedTrace(int realQueryCount) {
        List<SpanData> spans = new ArrayList<>(realQueryCount * 2 + 1);
        String rootId = "root";
        long order = 1;
        for (int i = 0; i < realQueryCount; i++) {
            String realId = "q" + i;
            spans.add(jdbcSpan(
                    "dup" + i, realId, "SELECT * FROM order_line WHERE order_id = " + i, "dataSource", order++));
            spans.add(jdbcSpan(
                    realId, rootId, "SELECT * FROM order_line WHERE order_id = " + i, "sample_app_db", order++));
        }
        spans.add(new SpanData(
                "bench-trace",
                rootId,
                null,
                "GET /orders",
                Span.Kind.SERVER,
                Instant.EPOCH,
                Instant.EPOCH.plusMillis(realQueryCount),
                Duration.ofMillis(realQueryCount),
                Map.of("http.method", "GET", "http.target", "/orders"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                order));
        return spans;
    }

    private static SpanData jdbcSpan(
            String spanId, String parentId, String query, String peerService, long creationOrder) {
        Instant start = Instant.EPOCH.plusMillis(creationOrder);
        return new SpanData(
                "bench-trace",
                spanId,
                parentId,
                "query",
                Span.Kind.CLIENT,
                start,
                start.plusMillis(1),
                Duration.ofMillis(1),
                Map.of("jdbc.query[0]", query, "peer.service", peerService),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                creationOrder);
    }
}
