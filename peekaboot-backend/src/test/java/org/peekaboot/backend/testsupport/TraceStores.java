package org.peekaboot.backend.testsupport;

import java.time.Duration;
import java.util.function.Consumer;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;

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
        return with(InMemoryTraceStore.DEFAULT_EXPIRE, customizer);
    }

    public static InMemoryTraceStore with(Duration expireAfter, Consumer<PeekabootTracingProperties> customizer) {
        PeekabootTracingProperties properties = new PeekabootTracingProperties();
        customizer.accept(properties);
        return new InMemoryTraceStore(
                properties.getMaxTraces(),
                properties.getMaxSpansPerTrace(),
                expireAfter,
                properties.getMaxErrorTraces(),
                properties.getMaxSlowTraces(),
                properties.getSlowTraceThresholdMs(),
                properties.getMaxLogsPerTrace());
    }
}
