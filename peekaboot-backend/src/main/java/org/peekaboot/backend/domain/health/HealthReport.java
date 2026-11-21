package org.peekaboot.backend.domain.health;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.insights.InsightsProblem;

/**
 * The application's health as {@code GET /api/health} serves it. The components nest as
 * {@code /actuator/health} nests them, and every node always carries all four keys: {@code status},
 * {@code details}, {@code components} and {@code connection}.
 *
 * @param windowMs the span of level 0, which {@code problems}, {@code traces} and {@code failures} cover
 * @param traces {@code null} while tracing is off
 */
public record HealthReport(
        String status,
        Map<String, Component> components,
        List<InsightsProblem> problems,
        long windowMs,
        @Nullable Traces traces,
        List<Failure> failures) {

    /**
     * One health contributor.
     *
     * @param details empty for a composite
     * @param components empty for an indicator
     * @param connection {@code null} for a component Peekaboot cannot describe
     */
    public record Component(
            String status,
            Map<String, Object> details,
            Map<String, Component> components,
            @Nullable HealthConnection connection) {}

    /** The traces of the Errors and Slow buckets that started inside the window. */
    public record Traces(int errors, int slow) {}

    /** One failure the error digest last saw inside the window. */
    public record Failure(
            @Nullable String type,
            @Nullable String route,
            @Nullable String applicationFrame,
            Instant lastSeen,
            @Nullable String lastMessage,
            String lastTraceId) {}
}
