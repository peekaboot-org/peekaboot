package org.peekaboot.backend.domain.trace;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP routes the All bucket's traces answered, busiest first.
 *
 * @param window what the routes were computed over
 */
public record RouteListResponse(Window window, List<RouteStats> routes) {

    /**
     * The traces behind the routes, every complete HTTP request the All bucket holds now whose
     * root carries an HTTP tag.
     *
     * @param from the earliest of their start times; null when there are none
     * @param to the latest of their start times; null when there are none
     * @param traceCount how many there are, the sum of every route's {@code count}
     */
    public record Window(@Nullable Instant from, @Nullable Instant to, int traceCount) {}
}
