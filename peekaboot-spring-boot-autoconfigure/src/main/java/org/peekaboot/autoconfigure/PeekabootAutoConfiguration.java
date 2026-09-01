package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HealthEndpoint.class, InfoEndpoint.class})
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@EnableConfigurationProperties({PeekabootProperties.class, UiTracingProperties.class})
@ComponentScan(
        basePackages = {
            "org.peekaboot.backend.controller",
            "org.peekaboot.backend.service",
            "org.peekaboot.backend.mapper",
            "org.peekaboot.backend.config",
            "org.peekaboot.backend.actuator"
        })
public class PeekabootAutoConfiguration {

    /** The one rule set every masking site shares: the actuator mappers, MetricsService and the maskers they build on it. */
    @Bean
    public MaskingEngine maskingEngine() {
        return new MaskingEngine();
    }
}
