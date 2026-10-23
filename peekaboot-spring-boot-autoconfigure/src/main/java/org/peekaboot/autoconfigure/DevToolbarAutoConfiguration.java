package org.peekaboot.autoconfigure;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.filter.DevToolbarFilter;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * The toolbar injection filter. It only needs the {@link Tracer}, whichever of Boot's two
 * tracing bridges registers it; the request and log capture it shows are
 * {@link TraceCaptureAutoConfiguration}'s.
 */
@AutoConfiguration(
        after = {PeekabootAutoConfiguration.class, PeekabootTracingAutoConfiguration.class},
        afterName = {
            "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration"
        })
@ConditionalOnPeekabootServlet
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.DEV_TOOLBAR_ENABLED)
@EnableConfigurationProperties(PeekabootTracingProperties.class)
public class DevToolbarAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DevToolbarAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ToolbarDataProvider toolbarDataProvider(PeekabootTracingProperties tracingProperties) {
        return new ToolbarDataProvider(tracingProperties);
    }

    // the missing-bean check matches the deduced generic FilterRegistrationBean<DevToolbarFilter>,
    // so the application's other filter registrations never back this one off
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Tracer.class)
    public FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
            ToolbarDataProvider toolbarDataProvider,
            Tracer tracer,
            PeekabootPaths peekabootPaths,
            Environment environment) {
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        // read via the Environment: springdoc's SwaggerUiConfigProperties may not be on the classpath
        registration.setFilter(new DevToolbarFilter(
                toolbarDataProvider,
                tracer,
                peekabootPaths,
                PeekabootPaths.swaggerUiPath(environment.getProperty("springdoc.swagger-ui.path")),
                PeekabootPaths.apiDocsPath(environment.getProperty("springdoc.api-docs.path"))));
        registration.addUrlPatterns("/*");
        // ERROR as well as REQUEST: the error page is rendered on its own dispatch, and the
        // bar belongs on it. What the bar reports there comes from the original request.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        log.debug("DevToolbarFilter registered for all URLs");
        return registration;
    }

    /** See {@link TomcatForwardResponseCustomizer}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({TomcatContextCustomizer.class, org.apache.catalina.Context.class})
    static class TomcatForwardConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TomcatForwardResponseCustomizer tomcatForwardResponseCustomizer() {
            return new TomcatForwardResponseCustomizer();
        }
    }
}
