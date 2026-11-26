package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.function.Consumer;
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

    /** Shows {@link ProblemRule#POOL_WAITING}, an ERROR: connections wait for the pool at every tick. */
    public static InsightsDataReader withConnectionsWaiting() {
        return ticked(registry ->
                Gauge.builder("hikaricp.connections.pending", () -> 3.0).register(registry));
    }

    /** Shows {@link ProblemRule#HEAP_NEAR_MAX}, a WARNING and the only problem: the heap is 95 percent full at every tick. */
    public static InsightsDataReader withHeapNearMax() {
        return ticked(registry -> {
            Gauge.builder("jvm.memory.used", () -> 95.0).tag("area", "heap").register(registry);
            Gauge.builder("jvm.memory.max", () -> 100.0).tag("area", "heap").register(registry);
        });
    }

    /** Shows {@link ProblemRule#HEAP_NEAR_MAX} from the OpenTelemetry heap meters, with no {@code area} tag. */
    public static InsightsDataReader withOpenTelemetryHeapNearMax() {
        return ticked(registry -> {
            Gauge.builder("jvm.memory.used", () -> 95.0)
                    .tag("jvm.memory.type", "heap")
                    .register(registry);
            Gauge.builder("jvm.memory.limit", () -> 100.0)
                    .tag("jvm.memory.type", "heap")
                    .register(registry);
        });
    }

    /** Shows {@link ProblemRule#CPU_HIGH} from the process CPU meter OpenTelemetry conventions name. */
    public static InsightsDataReader withOpenTelemetryCpuHigh() {
        return ticked(registry ->
                Gauge.builder("jvm.cpu.recent_utilization", () -> 0.9).register(registry));
    }

    private static InsightsDataReader ticked(Consumer<SimpleMeterRegistry> registerGauges) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // suppliers, never a weakly held state object: a GC between ticks would turn samples into NaN
        registerGauges.accept(registry);
        InsightsProperties properties = new InsightsProperties();
        InsightsService service = new InsightsService(
                registry, properties, new DefaultResourceLoader(), InsightsCollectors.noOpListener(), null);
        service.collector().tick(TICK_MS);
        service.collector().tick(2 * TICK_MS);
        return new InsightsDataReader(service, properties, SLOW_TRACE_THRESHOLD_MS);
    }
}
