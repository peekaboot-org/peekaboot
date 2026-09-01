package org.peekaboot.backend.domain.features;

/**
 * What the dashboard may show and the numbers it colours by - {@code GET /peekaboot/api/features}.
 * The thresholds are the effective ones {@code IssueDetector} raises issues at and the trace
 * store admits into its Slow bucket by, so the frontend never keeps a copy of its own.
 *
 * @param slowTraceThresholdMs the Slow bucket's admission threshold; null while tracing is
 *                             off, when neither the store nor its properties exist
 */
public record Features(
        boolean tracing,
        boolean metrics,
        boolean devToolbar,
        boolean unmaskingEnabled,
        boolean insights,
        long slowSpanThresholdMs,
        long verySlowSpanThresholdMs,
        long slowQueryThresholdMs,
        Long slowTraceThresholdMs) {}
