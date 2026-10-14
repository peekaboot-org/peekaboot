package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The single {@link PeekabootPaths} instance every consumer shares - the guard, both toolbar
 * filters, the error page, the tracing interceptor's exclusions and the span exporter's skip -
 * constructed once with the resolved {@code management.endpoints.web.base-path}, so the actuator
 * exclusion follows a relocated management base path, the resolved
 * {@code server.servlet.context-path}, so the exporter's skip holds for span path tags that carry
 * the context path, and the DispatcherServlet's prefix, which {@code spring.mvc.servlet.path}
 * moves the whole dashboard under.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
// every member is static, but Spring instantiates a configuration class whatever it holds
@SuppressWarnings("PMD.InstantiableUtilityClass")
public class PeekabootPathsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PeekabootPathsAutoConfiguration.class);

    /** Spring MVC serves the dashboard, so it sits wherever the DispatcherServlet is mapped. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DispatcherServletPath.class)
    static class SpringMvcPaths {

        @Bean
        @ConditionalOnMissingBean
        PeekabootPaths peekabootPaths(
                Environment environment, ObjectProvider<DispatcherServletPath> dispatcherServletPath) {
            DispatcherServletPath known = dispatcherServletPath.getIfAvailable();
            if (known == null) {
                log.warn("No DispatcherServletPath bean: Peekaboot assumes the DispatcherServlet is mapped at /"
                        + " and guards and serves its dashboard under /peekaboot/*");
                return paths(environment, "");
            }
            return paths(environment, known.getPrefix());
        }
    }

    /**
     * {@code DispatcherServletPath} lives in {@code spring-boot-webmvc}, which the starter does not
     * bring; without it nothing moves the dashboard off the root, and naming the type in a bean
     * method would fail startup.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath")
    static class RootPaths {

        @Bean
        @ConditionalOnMissingBean
        PeekabootPaths peekabootPaths(Environment environment) {
            return paths(environment, "");
        }
    }

    private static PeekabootPaths paths(Environment environment, String dispatcherServletPrefix) {
        return new PeekabootPaths(
                environment.getProperty("management.endpoints.web.base-path", "/actuator"),
                environment.getProperty("server.servlet.context-path", ""),
                dispatcherServletPrefix);
    }
}
