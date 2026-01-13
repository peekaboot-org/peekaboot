package net.osslabz.peekaboot.backend.integration;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import net.osslabz.peekaboot.backend.filter.DevToolbarFilter;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Shared test configuration for dev toolbar integration tests.
 * Provides common bean definitions for span storage, trace querying, and toolbar injection.
 */
@TestConfiguration
public class SharedToolbarTestConfig {

    @Bean
    InMemorySpanStore spanStore() {
        return new InMemorySpanStore(100, 50);
    }

    @Bean
    TraceQueryService traceQueryService(InMemorySpanStore spanStore) {
        return new TraceQueryService(spanStore);
    }

    @Bean
    ToolbarDataProvider toolbarDataProvider(
            TraceQueryService traceQueryService,
            PeekabookActuatorService actuatorService,
            PeekabootProperties properties) {
        return new ToolbarDataProvider(traceQueryService, actuatorService, properties.getBasePath());
    }

    @Bean
    FilterRegistrationBean<DevToolbarFilter> devToolbarFilter(
            ToolbarDataProvider toolbarDataProvider,
            PeekabootProperties properties) {
        FilterRegistrationBean<DevToolbarFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new DevToolbarFilter(toolbarDataProvider, properties.getBasePath()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        registration.setName("devToolbarFilter");
        return registration;
    }
}
