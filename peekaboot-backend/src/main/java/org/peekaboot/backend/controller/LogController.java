package org.peekaboot.backend.controller;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.log.LogSearchParameters;
import org.peekaboot.backend.domain.log.LogSearchResponse;
import org.peekaboot.backend.service.LogSearchService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The log search over every captured line, traced or not. */
@RestController
@RequestMapping(PeekabootPaths.BASE_PATH)
public class LogController {

    private final LogSearchService logSearchService;

    public LogController(LogSearchService logSearchService) {
        this.logSearchService = logSearchService;
    }

    /**
     * Binds the search's parameters from the query string alone (see {@link QueryParameterBinding}).
     * The name is the one Spring derives for the {@code @ModelAttribute} below.
     */
    @InitBinder("logSearchParameters")
    public void bindFromQueryStringOnly(WebDataBinder binder) {
        QueryParameterBinding.bindFromQueryStringOnly(binder);
    }

    /** See {@link LogSearchService#search}. Every parameter arrives as written; the service reads them. */
    @GetMapping(value = "/api/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public LogSearchResponse searchLogs(@ModelAttribute LogSearchParameters parameters) {
        return logSearchService.search(parameters);
    }
}
