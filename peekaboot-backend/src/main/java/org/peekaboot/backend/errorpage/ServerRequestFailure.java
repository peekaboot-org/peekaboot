package org.peekaboot.backend.errorpage;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Fails the request's server observation with the exception a page answers with 5xx. Spring
 * clears the error request attributes once an error view has rendered, before
 * {@link ServerHttpObservationFilter} reads them, so the server span would read OK. The span is
 * failed directly because the tracing handler reads the context's error only when the
 * observation itself is told, and the filter keeps the observation to itself.
 */
final class ServerRequestFailure {

    private ServerRequestFailure() {}

    static void record(HttpServletRequest request, Throwable failure) {
        ServerHttpObservationFilter.findObservationContext(request).ifPresent(context -> {
            context.setError(failure);
            TracingContext tracing = context.get(TracingContext.class);
            Span span = tracing != null ? tracing.getSpan() : null;
            if (span != null) {
                span.error(failure);
            }
        });
    }
}
