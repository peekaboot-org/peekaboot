package net.osslabz.peekaboot.tracing.autoconfigure;

import net.osslabz.peekaboot.tracing.bridge.brave.BraveSpanHandler;
import net.osslabz.peekaboot.tracing.store.InMemorySpanStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnClass(name = "brave.handler.SpanHandler")
@ConditionalOnProperty(prefix = "peekaboot.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BraveTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BraveSpanHandler braveSpanHandler(InMemorySpanStore store) {
        return new BraveSpanHandler(store);
    }
}
