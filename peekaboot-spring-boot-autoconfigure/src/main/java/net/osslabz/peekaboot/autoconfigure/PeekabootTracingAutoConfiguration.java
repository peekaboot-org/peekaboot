package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.backend.tracing.store.InMemoryTraceStore;
import net.osslabz.peekaboot.backend.tracing.store.TraceStore;
import net.osslabz.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Auto-configuration for Peekaboot tracing components.
 * Configures TraceStore as the central storage for all trace data.
 */
@AutoConfiguration
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
                properties.getSlowTraceThresholdMs()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceStoreEventListener traceStoreEventListener(TraceStore traceStore) {
        return new TraceStoreEventListener(traceStore);
    }
}
