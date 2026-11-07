package org.peekaboot.backend.controller;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.api.ApiDocument;
import org.peekaboot.backend.domain.api.OpenApiDocument;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The API described for a client that reads it. */
@RestController
public class PeekabootApiController {

    private final ApiDocument document;
    private final PeekabootPaths paths;

    public PeekabootApiController(ApiDocument document, PeekabootPaths paths) {
        this.document = document;
        this.paths = paths;
    }

    @GetMapping(value = ApiDocument.DOCUMENT_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public OpenApiDocument openApi() {
        return document.forServer(dispatcherServletRoot());
    }

    /** Read off the current request, since a request argument would leave {@link QueryParameterGuard} unable to tell what the handler reads. */
    private String dispatcherServletRoot() {
        return paths.dispatcherServletRoot(
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest());
    }
}
