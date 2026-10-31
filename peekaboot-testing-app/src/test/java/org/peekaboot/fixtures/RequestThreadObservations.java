package org.peekaboot.fixtures;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The name of every observation started while a request is served, under that request's URI: which
 * requests' work the application observes. Server observations are left out; they are the
 * requests themselves. A rejected observation never reaches a handler, so a name recorded here
 * was observed.
 */
public class RequestThreadObservations implements ObservationHandler<Observation.Context> {

    private final Map<String, Set<String>> namesByRequestUri = new ConcurrentHashMap<>();

    @Override
    public boolean supportsContext(Observation.Context context) {
        return !(context instanceof ServerRequestObservationContext);
    }

    @Override
    public void onStart(Observation.Context context) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            namesByRequestUri
                    .computeIfAbsent(attributes.getRequest().getRequestURI(), uri -> ConcurrentHashMap.newKeySet())
                    .add(context.getName());
        }
    }

    /** The names observed while serving any request whose URI starts with {@code uriPrefix}. */
    public Set<String> startedServing(String uriPrefix) {
        return namesByRequestUri.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(uriPrefix))
                .flatMap(entry -> entry.getValue().stream())
                .collect(Collectors.toSet());
    }
}
