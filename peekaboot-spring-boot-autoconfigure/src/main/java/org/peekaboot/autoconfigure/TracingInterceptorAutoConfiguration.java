package org.peekaboot.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.tracing.interceptor.TracingHandlerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(after = ObservationAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ENABLED, matchIfMissing = true)
public class TracingInterceptorAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TracingInterceptorAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TracingHandlerInterceptor tracingHandlerInterceptor(ObservationRegistry observationRegistry) {
        log.debug("Peekaboot TracingHandlerInterceptor registered for handler/view span capture");
        return new TracingHandlerInterceptor(observationRegistry);
    }

    // matched by name: a type check on WebMvcConfigurer would let any of the
    // application's own configurers back this registration off
    @Bean
    @ConditionalOnMissingBean(name = "tracingInterceptorConfigurer")
    public WebMvcConfigurer tracingInterceptorConfigurer(
            TracingHandlerInterceptor interceptor, PeekabootPaths peekabootPaths) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor)
                        .addPathPatterns("/**")
                        .excludePathPatterns(peekabootPaths.excludePatterns());
            }
        };
    }
}
