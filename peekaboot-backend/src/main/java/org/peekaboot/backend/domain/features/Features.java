package org.peekaboot.backend.domain.features;

/**
 * What the dashboard may show and the numbers it colours by - {@code GET /peekaboot/api/features}.
 * The thresholds are the effective ones {@code IssueDetector} raises issues at and the trace
 * store admits into its Slow bucket by, so the frontend never keeps a copy of its own.
 *
 * @param tracing whether the trace store exists, which decides whether the Traces tab's
 *                endpoints answer at all - not whether anything can ever fill it, see
 *                {@link #tracingSpansPossible}
 * @param tracingSpansPossible whether a span source is wired to that store. False is a hard
 *                             guarantee - the host has no OpenTelemetry SDK on its class path,
 *                             so the store can never receive a span - true is not a promise,
 *                             since sampling and the rest of the host's tracing setup stay
 *                             outside Peekaboot's view. Meaningless while {@link #tracing} is
 *                             false.
 * @param slowTraceThresholdMs the Slow bucket's admission threshold; null while tracing is
 *                             off, when neither the store nor its properties exist
 * @param maskLiteral the exact literal masked values are replaced with ({@code MaskingEngine.MASK_LITERAL});
 *                             the Request tab highlights masked headers by comparing against it
 */
public record Features(
        boolean tracing,
        boolean tracingSpansPossible,
        boolean metrics,
        boolean devToolbar,
        boolean unmaskingEnabled,
        boolean insights,
        long slowSpanThresholdMs,
        long verySlowSpanThresholdMs,
        long slowQueryThresholdMs,
        Long slowTraceThresholdMs,
        String maskLiteral) {}
