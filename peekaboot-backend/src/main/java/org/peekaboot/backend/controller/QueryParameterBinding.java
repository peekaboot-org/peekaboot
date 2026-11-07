package org.peekaboot.backend.controller;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.servlet.support.ExtendedServletRequestDataBinder;

/** Binds a record of query parameters for Peekaboot's controllers from the query string alone. */
public final class QueryParameterBinding {

    private QueryParameterBinding() {}

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
