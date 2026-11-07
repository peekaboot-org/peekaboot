package org.peekaboot.backend.controller;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.domain.errors.ErrorDigestParameters;
import org.peekaboot.backend.domain.errors.ErrorDigestResponse;
import org.peekaboot.backend.service.ErrorDigestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** Every failure the application's traces showed, kept past the traces themselves. */
@RestController
@RequestMapping(PeekabootPaths.BASE_PATH)
public class ErrorDigestController {

    private final ErrorDigestService errorDigestService;

    public ErrorDigestController(ErrorDigestService errorDigestService) {
        this.errorDigestService = errorDigestService;
    }

    /**
     * Binds the digest's parameters from the query string alone, each sent once (see
     * {@link QueryParameterBinding}). The name is the one Spring derives for the
     * {@code @ModelAttribute} below.
     */
    @InitBinder("errorDigestParameters")
    public void bindQueryParametersOnce(WebDataBinder binder, WebRequest request) {
        QueryParameterBinding.bindQueryParametersOnce(binder, request, ErrorDigestParameters.class);
    }

    /** See {@link ErrorDigestService#list}. Every parameter arrives as written; the service reads them. */
    @GetMapping(value = "/api/errors", produces = MediaType.APPLICATION_JSON_VALUE)
    public ErrorDigestResponse listErrors(@ModelAttribute ErrorDigestParameters parameters) {
        return errorDigestService.list(parameters);
    }
}
