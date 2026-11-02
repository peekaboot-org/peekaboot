package org.peekaboot.backend.domain.trace;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One method and route pattern, over the requests of {@link RouteListResponse.Window}.
 *
 * @param route the matched pattern, such as {@code /persons/{id}}, as the request recorded it; null for
 *     requests recorded without one, or with only a placeholder such as {@code NOT_FOUND}. Under Boot's
 *     default static-resource mapping ({@code spring.web.resources.add-mappings}), a request no
 *     controller matched carries {@code /**}
 * @param errorCount the requests whose outcome is {@code ERROR}
 * @param p50Ms the median duration, nearest rank over every request
 * @param p95Ms the 95th percentile duration, nearest rank over every request
 * @param latestErrorTraceId the newest request whose outcome is {@code ERROR}; null when none failed
 * @param latestSlowTraceId the newest request that reached {@code slow-trace-threshold-ms}, the one a
 *     {@code SLOW} reason names; null when none did
 * @param issueCounts per issue type, every type named, the requests carrying at least one such issue
 */
public record RouteStats(
        @Nullable String method,
        @Nullable String route,
        int count,
        int errorCount,
        long p50Ms,
        long p95Ms,
        long maxMs,
        @Nullable String latestErrorTraceId,
        @Nullable String latestSlowTraceId,
        Map<IssueType, Integer> issueCounts) {}
