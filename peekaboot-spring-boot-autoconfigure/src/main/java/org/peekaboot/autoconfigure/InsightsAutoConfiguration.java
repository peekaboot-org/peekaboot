package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.web.InsightsController;
import org.peekaboot.backend.insights.web.InsightsSsePublisher;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

/**
 * Auto-configuration for the Peekaboot insights dashboard: the metrics
 * collector/service, its SSE fan-out and the REST/streaming controller.
 * Requires a {@link MeterRegistry} bean, since insights has nothing to sample
 * without one - and that bean comes from Boot's metrics auto-configuration, which
 * sorts after every {@code org.peekaboot} class unless an edge says otherwise.
 */
@AutoConfiguration(
        after = {PeekabootAutoConfiguration.class, PeekabootStorageAutoConfiguration.class},
        afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.INSIGHTS_ENABLED, matchIfMissing = true)
@ConditionalOnBean(MeterRegistry.class)
@EnableConfigurationProperties(InsightsProperties.class)
public class InsightsAutoConfiguration {

    /**
     * Peekaboot's own mapper, like the REST responses: the SSE payloads are read by the
     * same dashboard. A user-supplied replacement takes over the whole lifecycle role -
     * it is the listener the service publishes through AND the SmartLifecycle whose
     * stop() completes the open emitters on context shutdown.
     */
    @Bean
    @ConditionalOnMissingBean
    public InsightsSsePublisher insightsSsePublisher() {
        return new InsightsSsePublisher(PeekabootJson.MAPPER);
    }

    @Bean
    @ConditionalOnMissingBean
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
    @ConditionalOnMissingBean
    public InsightsController insightsController(
            InsightsService insightsService, InsightsSsePublisher insightsSsePublisher) {
        return new InsightsController(insightsService, insightsSsePublisher);
    }
}
