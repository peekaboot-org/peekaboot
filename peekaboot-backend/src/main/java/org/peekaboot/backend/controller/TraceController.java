package org.peekaboot.backend.controller;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.trace.TraceListParameters;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.problem.ApiProblem;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.service.TraceInsightsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** The trace listing and one trace by its id. */
@RestController
@RequestMapping(PeekabootPaths.BASE_PATH)
public class TraceController {

    private final TraceInsightsService traceInsightsService;

    public TraceController(TraceInsightsService traceInsightsService) {
        this.traceInsightsService = traceInsightsService;
    }

    /**
     * Binds the listing's parameters from the query string alone, each sent once (see
     * {@link QueryParameterBinding}). The name is the one Spring derives for the
     * {@code @ModelAttribute} below.
     */
    @InitBinder("traceListParameters")
    public void bindQueryParametersOnce(WebDataBinder binder, WebRequest request) {
        QueryParameterBinding.bindQueryParametersOnce(binder, request, TraceListParameters.class);
    }

    /** See {@link TraceInsightsService#listTraces}. Every parameter arrives as written; the service reads them. */
    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceListResponse listTraces(@ModelAttribute TraceListParameters parameters) {
        return traceInsightsService.listTraces(parameters);
    }

    @GetMapping(value = "/api/traces/{traceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TraceResource getTrace(@PathVariable(name = "traceId") String traceId) {
        return traceInsightsService
                .getTrace(traceId)
                .orElseThrow(() -> new ApiProblemException(ApiProblem.notFound(
                        "No trace " + traceId + " in the store. The id is unknown or evicted, or tracing is off.")));
    }
}
