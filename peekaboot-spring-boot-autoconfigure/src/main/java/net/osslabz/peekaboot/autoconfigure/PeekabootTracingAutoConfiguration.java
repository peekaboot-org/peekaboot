package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.tracing.autoconfigure.PeekabootTracingProperties;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.TraceDataStorage;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Auto-configuration for Peekaboot tracing components.
 * Configures TraceDataStorage as the central storage for all trace data
 * and TraceQueryService for querying traces.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PeekabootTracingProperties.class)
public class PeekabootTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceDataStorage traceDataStorage(PeekabootTracingProperties properties) {
        return new TraceDataStorage(
                properties.getMaxTraces(),
                properties.getMaxSpansPerTrace(),
                Duration.ofMinutes(30)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceQueryService traceQueryService(TraceDataStorage storage) {
        return new TraceQueryService(storage);
    }
}
