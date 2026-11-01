package org.peekaboot.backend.controller;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.problem.ApiProblem;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The trace listing and one trace by its id. */
@RestController
@RequestMapping(PeekabootPaths.BASE_PATH)
public class TraceController {

    private final TraceInsightsService traceInsightsService;

    public TraceController(TraceInsightsService traceInsightsService) {
        this.traceInsightsService = traceInsightsService;
    }

    /**
     * The trace listing. {@code rootActionType} takes a comma-separated include-list of root action
     * types; a request that names none gets the default view, which leaves out routine maintenance,
     * and {@code *} gets every type. {@code view=full} adds each row's data. See
     * {@link TraceInsightsService#listTraces}.
     */
    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceListResponse listTraces(
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "bucket", required = false) String bucket,
            @RequestParam(name = "rootActionType", required = false) String rootActionType,
            @RequestParam(name = "rootOperation", required = false) String rootOperation,
            @RequestParam(name = "view", required = false) String view) {
        return traceInsightsService.listTraces(
                sanitizeLimit(limit), parseBucket(bucket), rootActionType, rootOperation, view);
    }

    private static TraceBucket parseBucket(String value) {
        return value == null || value.isBlank()
                ? TraceBucket.ALL
                : RequestParameters.enumValue("bucket", TraceBucket.class, value);
    }

    /** Negative limits throw from Stream.limit; the cap bounds the response size. */
    private int sanitizeLimit(int limit) {
        return Math.clamp(limit, 0, 10_000);
    }

    @GetMapping(value = "/api/traces/{traceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceResource getTrace(@PathVariable(name = "traceId") String traceId) {
        return traceInsightsService
                .getTrace(traceId)
                .orElseThrow(() -> new ApiProblemException(ApiProblem.notFound(
                        "No trace " + traceId + " in the store. The id is unknown or evicted, or tracing is off.")));
    }
}
