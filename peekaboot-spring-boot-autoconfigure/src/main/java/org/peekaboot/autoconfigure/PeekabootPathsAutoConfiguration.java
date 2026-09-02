package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootPaths;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The single {@link PeekabootPaths} instance every consumer shares - both toolbar filters,
 * the tracing interceptor's exclusions and the span exporter's skip - constructed once
 * with the resolved {@code management.endpoints.web.base-path}, so the actuator exclusion
 * follows a relocated management base path, and the resolved
 * {@code server.servlet.context-path}, so the exporter's skip holds for span path tags
 * that carry the context path.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
public class PeekabootPathsAutoConfiguration {

    @Bean
    public PeekabootPaths peekabootPaths(Environment environment) {
        return new PeekabootPaths(
                environment.getProperty("management.endpoints.web.base-path", "/actuator"),
                environment.getProperty("server.servlet.context-path", ""));
    }
}
