package org.peekaboot.autoconfigure;

import io.micrometer.observation.ObservationPredicate;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.config.PeekabootRequestObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Boot applies every {@link ObservationPredicate} bean to the application's registry, so this one
 * takes the dashboard's own traffic out of the request metrics, the http-* insights and the
 * traces in one place. Independent of {@code peekaboot.tracing.enabled}: the metrics need it too.
 */
@AutoConfiguration(after = PeekabootPathsAutoConfiguration.class)
@ConditionalOnPeekabootServlet
@ConditionalOnClass({ObservationPredicate.class, ServerRequestObservationContext.class})
public class PeekabootObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PeekabootRequestObservationPredicate peekabootRequestObservationPredicate(PeekabootPaths paths) {
        return new PeekabootRequestObservationPredicate(paths);
    }
}
