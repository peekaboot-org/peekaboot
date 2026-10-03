package org.peekaboot.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.peekaboot.backend.tracing.listener.EventListenerObservationPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;

@AutoConfiguration(after = ObservationAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ENABLED, matchIfMissing = true)
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_EVENT_LISTENERS, matchIfMissing = true)
public final class EventListenerInstrumentationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EventListenerInstrumentationAutoConfiguration.class);

    // every member is static, so PMD's InstantiableUtilityClass wants this class final and not instantiable
    private EventListenerInstrumentationAutoConfiguration() {}

    // handed the registry lazily: Boot configures it in a post-processor that does not exist yet
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnMissingBean(name = "peekabootEventListenerObservationPostProcessor")
    public static EventListenerObservationPostProcessor peekabootEventListenerObservationPostProcessor(
            ObjectProvider<ObservationRegistry> observationRegistry) {
        log.debug("Peekaboot EventListenerObservationPostProcessor registered for event listener span capture");
        // a listener call must never fail over Peekaboot: with no unique registry it runs unobserved
        return new EventListenerObservationPostProcessor(
                () -> observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
    }
}
