package org.peekaboot.backend.domain.trace;

import java.util.Map;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

public record HttpExchange(HttpRequest request, HttpResponse response) {

    public static HttpExchange from(RequestCompletedEvent event) {
        HttpRequest.Controller controller =
                new HttpRequest.Controller(event.controllerClass(), event.controllerMethod());
        HttpRequest.Params params = new HttpRequest.Params(
                event.queryParams() != null ? event.queryParams() : Map.of(),
                event.formParams() != null ? event.formParams() : Map.of());
        HttpRequest request = new HttpRequest(
                event.method(),
                event.path(),
                event.queryString(),
                event.requestHeaders() != null ? event.requestHeaders() : Map.of(),
                controller,
                params);
        HttpResponse response =
                new HttpResponse(event.status(), event.responseHeaders() != null ? event.responseHeaders() : Map.of());
        return new HttpExchange(request, response);
    }
}
