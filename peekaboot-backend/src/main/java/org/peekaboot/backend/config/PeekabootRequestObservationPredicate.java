package org.peekaboot.backend.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Keeps Peekaboot's own requests out of the application's observations: the server observation
 * of a dashboard request, and every observation started while one is served. The first alone
 * would leave the second as root traces of their own, because Micrometer does not make a rejected
 * observation the thread's current one. The server observation is judged by its own request,
 * since it starts before {@code RequestContextFilter} has bound one; everything after that reads
 * {@link RequestContextHolder}.
 */
public final class PeekabootRequestObservationPredicate implements ObservationPredicate {

    private final PeekabootPaths paths;

    public PeekabootRequestObservationPredicate(PeekabootPaths paths) {
        this.paths = paths;
    }

    @Override
    public boolean test(String name, Observation.Context context) {
        HttpServletRequest request =
                context instanceof ServerRequestObservationContext server ? server.getCarrier() : currentRequest();
        return request == null || !paths.isDashboardRequest(request);
    }

    private static @Nullable HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
