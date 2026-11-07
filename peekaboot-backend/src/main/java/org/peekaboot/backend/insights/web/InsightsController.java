package org.peekaboot.backend.insights.web;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.controller.QueryParameterBinding;
import org.peekaboot.backend.insights.InsightsConfigResponse;
import org.peekaboot.backend.insights.InsightsDataParameters;
import org.peekaboot.backend.insights.InsightsDataReader;
import org.peekaboot.backend.insights.InsightsDataResponse;
import org.peekaboot.backend.insights.InsightsService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping(PeekabootPaths.BASE_PATH + "/api/insights")
public class InsightsController {

    private final InsightsService service;
    private final InsightsSsePublisher publisher;
    private final InsightsDataReader dataReader;

    public InsightsController(InsightsService service, InsightsSsePublisher publisher, InsightsDataReader dataReader) {
        this.service = service;
        this.publisher = publisher;
        this.dataReader = dataReader;
    }

    /**
     * Binds the data request's parameters from the query string alone, each sent once (see
     * {@link QueryParameterBinding}). The name is the one Spring derives for the
     * {@code @ModelAttribute} below.
     */
    @InitBinder("insightsDataParameters")
    public void bindQueryParametersOnce(WebDataBinder binder, WebRequest request) {
        QueryParameterBinding.bindQueryParametersOnce(binder, request, InsightsDataParameters.class);
    }

    @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public InsightsConfigResponse config() {
        return service.config();
    }

    /** See {@link InsightsDataReader#read}. Every parameter arrives as written; the reader reads them. */
    @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public InsightsDataResponse data(@ModelAttribute InsightsDataParameters parameters) {
        return dataReader.read(parameters);
    }

    @GetMapping("/stream")
    public SseEmitter stream() {
        return publisher.subscribe();
    }
}
