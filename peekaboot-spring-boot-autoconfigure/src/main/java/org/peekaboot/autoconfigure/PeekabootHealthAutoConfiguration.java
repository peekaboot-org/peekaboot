package org.peekaboot.autoconfigure;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.actuator.InsightsSource;
import org.peekaboot.backend.actuator.parsed.ActuatorParsedData;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.controller.HealthController;
import org.peekaboot.backend.health.HealthComponents;
import org.peekaboot.backend.health.HealthConnectionSource;
import org.peekaboot.backend.health.HealthReportService;
import org.peekaboot.backend.health.HealthStatusCodes;
import org.peekaboot.backend.health.RecentFindings;
import org.peekaboot.backend.insights.InsightsDataReader;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The health report: {@code GET /api/health}.
 *
 * <p>Peekaboot adds no timeout of its own: a health check that hangs holds the report for as
 * long as the check takes.
 *
 * <p>Every collaborator is read through an {@link ObjectProvider} at bean creation, so no
 * ordering relationship with the auto-configurations that provide them is needed.
 */
@AutoConfiguration
@ConditionalOnPeekabootServlet
@ConditionalOnClass({HealthEndpoint.class, WebMvcConfigurer.class})
@Import({HealthStatusCodesConfiguration.class, HealthConnectionSourcesConfiguration.class})
public class PeekabootHealthAutoConfiguration {

    /**
     * Reads the health from the {@link InsightsSource} that carries it, so an application that
     * replaced that source is honoured and health is looked up once.
     */
    @Bean
    @ConditionalOnMissingBean
    public HealthReportService peekabootHealthReportService(
            ObjectProvider<InsightsSource> sources,
            ActuatorResponseParser parser,
            MaskingEngine maskingEngine,
            ObjectProvider<HealthConnectionSource> connectionSources,
            ObjectProvider<InsightsDataReader> insights,
            ObjectProvider<TraceStore> traceStore,
            ObjectProvider<ErrorDigest> errorDigest,
            InsightsProperties insightsProperties,
            HealthStatusCodes statusCodes,
            PeekabootProperties properties) {
        Supplier<@Nullable Object> health = sources.orderedStream()
                .filter(source -> ActuatorParsedData.HEALTH.equals(source.id()))
                .findFirst()
                .map(InsightsSource::read)
                .orElse(null);
        InsightsProperties.Level levelZero = insightsProperties.getLevels().getFirst();
        RecentFindings findings = new RecentFindings(
                insights.getIfAvailable(),
                traceStore.getIfAvailable(),
                errorDigest.getIfAvailable(),
                maskingEngine,
                Duration.ofMillis(levelZero.intervalMillis() * levelZero.getSize()),
                Clock.systemUTC());
        return new HealthReportService(
                health,
                parser,
                new HealthComponents(
                        maskingEngine, connectionSources.orderedStream().toList()),
                findings,
                statusCodes,
                properties.getHealth().isFailOnProblems());
    }

    @Bean
    @ConditionalOnMissingBean
    public HealthController peekabootHealthController(HealthReportService service) {
        return new HealthController(service);
    }
}
