package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.web.InsightsController;
import org.peekaboot.backend.insights.web.InsightsSsePublisher;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for the Peekaboot insights dashboard: the metrics
 * collector/service, its SSE fan-out and the REST/streaming controller.
 * Requires a {@link MeterRegistry} bean, since insights has nothing to sample
 * without one.
 */
@AutoConfiguration(after = {PeekabootAutoConfiguration.class, PeekabootStorageAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true")
@ConditionalOnBooleanProperty(name = "peekaboot.insights.enabled", matchIfMissing = true)
@ConditionalOnBean(MeterRegistry.class)
@EnableConfigurationProperties(InsightsProperties.class)
public class InsightsAutoConfiguration {

    @Bean
    public InsightsSsePublisher insightsSsePublisher(ObjectMapper objectMapper) {
        return new InsightsSsePublisher(objectMapper);
    }

    @Bean
    public InsightsService insightsService(
            MeterRegistry meterRegistry,
            InsightsProperties properties,
            ResourceLoader resourceLoader,
            InsightsSsePublisher insightsSsePublisher,
            ObjectProvider<StorageDirectory> storageDirectory) {
        return new InsightsService(
                meterRegistry, properties, resourceLoader, insightsSsePublisher, storageDirectory.getIfAvailable());
    }

    @Bean
    public InsightsController insightsController(
            InsightsService insightsService, InsightsSsePublisher insightsSsePublisher) {
        return new InsightsController(insightsService, insightsSsePublisher);
    }
}
