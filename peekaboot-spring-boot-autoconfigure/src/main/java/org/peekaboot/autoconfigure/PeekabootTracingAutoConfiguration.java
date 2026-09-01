package org.peekaboot.autoconfigure;

import java.time.Duration;
import org.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Peekaboot tracing components.
 * Configures TraceStore as the central storage for all trace data. Servlet-only because
 * everything that reads the store - the trace service, controller and filters - is.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PeekabootTracingProperties.class)
public class PeekabootTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceStore traceStore(PeekabootTracingProperties properties) {
        return new InMemoryTraceStore(
                properties.getMaxTraces(),
                properties.getMaxSpansPerTrace(),
                Duration.ofMinutes(30),
                properties.getMaxErrorTraces(),
                properties.getMaxSlowTraces(),
                properties.getSlowTraceThresholdMs(),
                properties.getMaxLogsPerTrace());
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceStoreEventListener traceStoreEventListener(TraceStore traceStore) {
        return new TraceStoreEventListener(traceStore);
    }
}
