package org.peekaboot.backend.testsupport;

import java.util.Set;
import java.util.function.Consumer;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;

/**
 * Builds {@link InMemoryTraceStore}s the way the auto-configuration does: every limit from
 * {@link PeekabootTracingProperties}, so a test that overrides one names only that one.
 */
public final class TraceStores {

    private TraceStores() {}

    public static InMemoryTraceStore withDefaults() {
        return with(properties -> {});
    }

    public static InMemoryTraceStore with(Consumer<PeekabootTracingProperties> customizer) {
        PeekabootTracingProperties properties = new PeekabootTracingProperties();
        customizer.accept(properties);
        return new InMemoryTraceStore(properties);
    }

    /**
     * A store with the defaults whose first {@code bucketsOf} call is followed at once by {@code landing},
     * so a test can tell whether a reader mapped a trace before or after it read the buckets.
     */
    public static InMemoryTraceStore landingAfterTheFirstBucketRead(SpanData landing) {
        return new InMemoryTraceStore(new PeekabootTracingProperties()) {
            private boolean landed;

            @Override
            public Set<TraceBucket> bucketsOf(String traceId) {
                Set<TraceBucket> read = super.bucketsOf(traceId);
                if (!landed) {
                    landed = true;
                    addSpan(landing);
                }
                return read;
            }
        };
    }
}
