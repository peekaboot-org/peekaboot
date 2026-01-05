package net.osslabz.peekaboot.tracing.autoconfigure;

import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PeekabootTracingProperties.class)
public class PeekabootTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InMemorySpanStore inMemorySpanStore(PeekabootTracingProperties properties) {
        return new InMemorySpanStore(
                properties.getMaxTraces(),
                properties.getMaxSpansPerTrace()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceQueryService traceQueryService(InMemorySpanStore store) {
        return new TraceQueryService(store);
    }
}
