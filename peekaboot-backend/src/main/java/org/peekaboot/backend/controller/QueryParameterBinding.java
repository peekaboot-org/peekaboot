package org.peekaboot.backend.controller;

import java.lang.reflect.RecordComponent;
import org.peekaboot.backend.problem.ApiProblemException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.support.ExtendedServletRequestDataBinder;

/** Binds a record of query parameters for Peekaboot's controllers from the query string alone, each parameter once. */
public final class QueryParameterBinding {

    private QueryParameterBinding() {}

    /**
     * Turns off Spring's fallback to a same-named request header, so a client's or proxy's header
     * cannot set a parameter, and refuses a component of {@code parameters} sent more than once.
     * Spring joins a repeat with commas before any reader sees it, and a route or a path can carry a
     * comma of its own, so the raw values are counted here. A parameter the record does not name is
     * left alone.
     */
    public static void bindQueryParametersOnce(
            WebDataBinder binder, WebRequest request, Class<? extends Record> parameters) {
        if (binder instanceof ExtendedServletRequestDataBinder requestBinder) {
            requestBinder.setHeaderPredicate(name -> false);
        }
        for (RecordComponent component : parameters.getRecordComponents()) {
            String name = component.getName();
            String[] values = request.getParameterValues(name);
            if (values != null && values.length > 1) {
                throw ApiProblemException.badParameter(
                        name, name + " was sent " + values.length + " times. Send it once.");
            }
        }
    }
}
