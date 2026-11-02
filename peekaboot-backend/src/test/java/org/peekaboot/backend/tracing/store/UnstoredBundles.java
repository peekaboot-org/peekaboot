package org.peekaboot.backend.tracing.store;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Bundles no store keeps, for tests that drive a bundle directly through the store's own methods. */
public final class UnstoredBundles {

    /** The filing step of a bundle no store keeps: it files nothing. */
    public static final Consumer<TraceDataBundle> UNFILED = bundle -> {};

    private UnstoredBundles() {}

    /** A bundle in a run of its own that never ends. */
    public static TraceDataBundle bundle(String traceId, int maxSpans, int maxLogs) {
        return new TraceDataBundle(traceId, maxSpans, maxLogs, new TraceRun(null));
    }

    /** Such a bundle whose span ids resolve through {@code resolveSpanId}, for a test that moves redirects between two reads. */
    public static TraceDataBundle resolvingSpanIds(
            String traceId, int maxSpans, int maxLogs, UnaryOperator<String> resolveSpanId) {
        return new TraceDataBundle(traceId, maxSpans, maxLogs, new TraceRun(null)) {
            @Override
            public String resolveSpanId(String spanId) {
                return resolveSpanId.apply(spanId);
            }
        };
    }
}
