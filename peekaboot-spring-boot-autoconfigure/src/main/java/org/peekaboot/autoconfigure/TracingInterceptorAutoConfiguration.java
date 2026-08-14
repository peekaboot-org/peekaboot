package org.peekaboot.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.peekaboot.backend.tracing.interceptor.TracingHandlerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Auto-configuration for tracing interceptor that captures controller and view rendering spans.
 */
@AutoConfiguration(after = ObservationAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TracingInterceptorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingInterceptorAutoConfiguration.class);

    @Bean
    public TracingHandlerInterceptor tracingHandlerInterceptor(ObservationRegistry observationRegistry) {
        log.info("Peekaboot TracingHandlerInterceptor registered for handler/view span capture");
        return new TracingHandlerInterceptor(observationRegistry);
    }

    @Bean
    public WebMvcConfigurer tracingInterceptorConfigurer(TracingHandlerInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor)
                        .addPathPatterns("/**")
                        .excludePathPatterns("/peekaboot/**", "/actuator/**", "/static/**", "/webjars/**", "/error");
            }
        };
    }
}
