package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * A real {@link InsightsDataReader} over a collector this fixture has ticked, for tests outside this
 * package, which cannot reach the collector to tick it themselves.
 */
public final class TickedInsights {

    private static final long TICK_MS = 10_000;
    private static final long SLOW_TRACE_THRESHOLD_MS = 1000;

    private TickedInsights() {}

    /** Shows {@link ProblemRule#POOL_WAITING}: connections wait for the pool at every tick. */
    public static InsightsDataReader withConnectionsWaiting() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // a supplier, never a weakly held state object: a GC between ticks would turn samples into NaN
        Gauge.builder("hikaricp.connections.pending", () -> 3.0).register(registry);
        InsightsProperties properties = new InsightsProperties();
        InsightsService service = new InsightsService(
                registry, properties, new DefaultResourceLoader(), InsightsCollectors.noOpListener(), null);
        service.collector().tick(TICK_MS);
        service.collector().tick(2 * TICK_MS);
        return new InsightsDataReader(service, properties, SLOW_TRACE_THRESHOLD_MS);
    }
}
