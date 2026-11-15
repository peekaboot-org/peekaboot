package org.peekaboot.backend.controller;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.servlet.support.ExtendedServletRequestDataBinder;

/**
 * Binds a record of query parameters for Peekaboot's controllers from the query string alone.
 * The package scope keeps it away from the application's controllers.
 */
@ControllerAdvice(basePackages = "org.peekaboot.backend")
public class QueryParameterBinding {

    /** Runs for every binder of a Peekaboot controller, so an endpoint that binds a record declares none. */
    @InitBinder
    void queryStringOnly(WebDataBinder binder) {
        bindFromQueryStringOnly(binder);
    }

    /**
     * Turns off Spring's fallback to a same-named request header, so a client's or proxy's header
     * cannot set a parameter. {@link QueryParameterGuard} has refused an unknown or repeated
     * parameter before binding starts.
     */
    public static void bindFromQueryStringOnly(WebDataBinder binder) {
        if (binder instanceof ExtendedServletRequestDataBinder requestBinder) {
            requestBinder.setHeaderPredicate(name -> false);
        }
    }
}
