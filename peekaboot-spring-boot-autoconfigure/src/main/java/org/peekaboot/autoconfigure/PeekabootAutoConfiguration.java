package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import org.peekaboot.backend.actuator.InsightsSource;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.config.PeekabootWebConfig;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.lifecycle.DataSourceMetadataList;
import org.peekaboot.backend.mapper.trace.HibernateSqlFormatter;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.bridge.otel.OtelSpanExporter;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Core auto-configuration: the dashboard controller, the actuator/trace services, their
 * mappers and the servlet web config, each an explicit {@link Bean @Bean} method. Every
 * bean is {@link ConditionalOnMissingBean @ConditionalOnMissingBean} and keeps the name
 * component scanning would derive from its class (some are looked up by exactly that
 * name, e.g. {@code ServerUrlResolver#DASHBOARD_CONFIG_BEAN_NAME}), so an application
 * bean of the same type or name replaces the default instead of colliding with it.
 *
 * <p>Binds the tracing and insights properties whatever their switches say, so the Config
 * tab shows the switch that turned a feature off.
 */
@AutoConfiguration(after = {PeekabootTracingAutoConfiguration.class, OtelTracingAutoConfiguration.class})
@ConditionalOnPeekabootServlet
@ConditionalOnClass(HealthEndpoint.class)
@EnableConfigurationProperties({PeekabootProperties.class, PeekabootTracingProperties.class, InsightsProperties.class})
@Import({StackTraceRulesConfiguration.class, MaskingConfiguration.class})
public class PeekabootAutoConfiguration {

    /**
     * Dashboard resource handlers, redirects, interceptor and JSON converter.
     * {@code ServerUrlResolver} checks for this bean by name to decide whether the
     * application serves the dashboard.
     */
    @Bean
    @ConditionalOnMissingBean
    public PeekabootWebConfig peekabootWebConfig() {
        return new PeekabootWebConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActuatorResponseParser actuatorResponseParser() {
        return new ActuatorResponseParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public PeekabootActuatorService peekabootActuatorService(ObjectProvider<InsightsSource> sources) {
        return new PeekabootActuatorService(sources.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceTreeMapper traceTreeMapper(MaskingEngine maskingEngine, ObjectProvider<SqlFormatter> sqlFormatter) {
        return new TraceTreeMapper(maskingEngine, sqlFormatter.getIfAvailable(() -> SqlFormatter.NONE));
    }

    @Bean
    @ConditionalOnMissingBean
    public IssueDetector issueDetector(PeekabootTracingProperties tracingProperties) {
        return new IssueDetector(tracingProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryExtractor queryExtractor() {
        return new QueryExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActuatorInsightsService actuatorInsightsService(
            PeekabootActuatorService peekabootActuatorService,
            ActuatorResponseParser actuatorResponseParser,
            MaskingEngine maskingEngine,
            ObjectProvider<DataSourceMetadataList> dataSourceMetadataList) {
        return new ActuatorInsightsService(
                peekabootActuatorService, actuatorResponseParser, maskingEngine, dataSourceMetadataList);
    }

    /** The registry is absent on an application without Boot's metrics auto-configuration; the service then reports itself unavailable. */
    @Bean
    @ConditionalOnMissingBean
    public MetricsService metricsService(ObjectProvider<MeterRegistry> meterRegistry, MaskingEngine maskingEngine) {
        return new MetricsService(meterRegistry.getIfAvailable(), maskingEngine);
    }

    /** The trace store is absent while {@code peekaboot.tracing.enabled} is false; the service then reports tracing unavailable. */
    @Bean
    @ConditionalOnMissingBean
    public TraceInsightsService traceInsightsService(
            ObjectProvider<TraceStore> traceStore,
            TraceTreeMapper traceTreeMapper,
            IssueDetector issueDetector,
            QueryExtractor queryExtractor,
            StackTraceRules stackTraceRules) {
        return new TraceInsightsService(
                traceStore.getIfAvailable(), traceTreeMapper, issueDetector, queryExtractor, stackTraceRules);
    }

    @Bean
    @ConditionalOnMissingBean
    public PeekabootController peekabootController(
            ActuatorInsightsService actuatorInsightsService,
            TraceInsightsService traceInsightsService,
            MetricsService metricsService,
            PeekabootProperties properties,
            PeekabootTracingProperties tracingProperties,
            ObjectProvider<InsightsService> insightsService,
            ObjectProvider<OtelSpanExporter> otelSpanExporter) {
        return new PeekabootController(
                actuatorInsightsService,
                traceInsightsService,
                metricsService,
                properties,
                tracingProperties,
                insightsService.getIfAvailable(),
                otelSpanExporter.getIfAvailable() != null);
    }

    /** Only with Hibernate on the classpath; without it every query's {@code formatted} SQL is null. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.hibernate.engine.jdbc.internal.FormatStyle")
    static class HibernateSqlFormatterConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SqlFormatter hibernateSqlFormatter() {
            return new HibernateSqlFormatter();
        }
    }
}
