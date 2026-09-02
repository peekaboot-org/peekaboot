package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.peekaboot.backend.actuator.parsed.ActuatorResponseParser;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.config.PeekabootWebConfig;
import org.peekaboot.backend.config.UiTracingProperties;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.mapper.actuator.ApplicationMapper;
import org.peekaboot.backend.mapper.actuator.ConfigMapper;
import org.peekaboot.backend.mapper.actuator.DataSourceMapper;
import org.peekaboot.backend.mapper.actuator.EnvironmentMapper;
import org.peekaboot.backend.mapper.actuator.FlywayMapper;
import org.peekaboot.backend.mapper.actuator.HealthMapper;
import org.peekaboot.backend.mapper.actuator.LoggersMapper;
import org.peekaboot.backend.mapper.actuator.RuntimeMapper;
import org.peekaboot.backend.mapper.actuator.ScheduledTasksMapper;
import org.peekaboot.backend.mapper.trace.IssueDetector;
import org.peekaboot.backend.mapper.trace.QueryExtractor;
import org.peekaboot.backend.mapper.trace.TraceTreeMapper;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.service.ActuatorInsightsService;
import org.peekaboot.backend.service.MetricsService;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Core auto-configuration: the dashboard controller, the actuator/trace services, their
 * mappers and the servlet web config, each an explicit {@link Bean @Bean} method. Every
 * bean is {@link ConditionalOnMissingBean @ConditionalOnMissingBean} and keeps the name
 * component scanning would derive from its class (some are looked up by exactly that
 * name, e.g. {@code ServerUrlResolver#DASHBOARD_CONFIG_BEAN_NAME}), so an application
 * bean of the same type or name replaces the default instead of colliding with it.
 */
@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HealthEndpoint.class, InfoEndpoint.class})
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@EnableConfigurationProperties({PeekabootProperties.class, UiTracingProperties.class})
public class PeekabootAutoConfiguration {

    /** The one rule set every masking site shares: the actuator mappers, MetricsService and the maskers they build on it. */
    @Bean
    @ConditionalOnMissingBean
    public MaskingEngine maskingEngine() {
        return new MaskingEngine();
    }

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
    public PeekabootActuatorService peekabootActuatorService(
            ApplicationContext context,
            ObjectProvider<HealthEndpoint> healthEndpoint,
            ParameterValueMapper parameterMapper,
            EndpointMediaTypes mediaTypes,
            ObjectProvider<PathMapper> pathMappers,
            ObjectProvider<AdditionalPathsMapper> additionalPathsMappers,
            ObjectProvider<OperationInvokerAdvisor> advisors) {
        return new PeekabootActuatorService(
                context, healthEndpoint, parameterMapper, mediaTypes, pathMappers, additionalPathsMappers, advisors);
    }

    @Bean
    @ConditionalOnMissingBean
    public HealthMapper healthMapper(MaskingEngine maskingEngine) {
        return new HealthMapper(maskingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuntimeMapper runtimeMapper() {
        return new RuntimeMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public DataSourceMapper dataSourceMapper(MaskingEngine maskingEngine) {
        return new DataSourceMapper(maskingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApplicationMapper applicationMapper(MaskingEngine maskingEngine) {
        return new ApplicationMapper(maskingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public EnvironmentMapper environmentMapper(MaskingEngine maskingEngine) {
        return new EnvironmentMapper(maskingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public LoggersMapper loggersMapper() {
        return new LoggersMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public FlywayMapper flywayMapper() {
        return new FlywayMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigMapper configMapper(MaskingEngine maskingEngine) {
        return new ConfigMapper(maskingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduledTasksMapper scheduledTasksMapper() {
        return new ScheduledTasksMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceTreeMapper traceTreeMapper(MaskingEngine maskingEngine) {
        return new TraceTreeMapper(maskingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public IssueDetector issueDetector(UiTracingProperties uiTracingProperties) {
        return new IssueDetector(uiTracingProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryExtractor queryExtractor(MaskingEngine maskingEngine) {
        return new QueryExtractor(maskingEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActuatorInsightsService actuatorInsightsService(
            PeekabootActuatorService peekabootActuatorService,
            ActuatorResponseParser actuatorResponseParser,
            HealthMapper healthMapper,
            RuntimeMapper runtimeMapper,
            DataSourceMapper dataSourceMapper,
            ApplicationMapper applicationMapper,
            EnvironmentMapper environmentMapper,
            LoggersMapper loggersMapper,
            FlywayMapper flywayMapper,
            ConfigMapper configMapper,
            ScheduledTasksMapper scheduledTasksMapper,
            ObjectProvider<List<DataSourceMetadata>> dataSourceMetadataList) {
        return new ActuatorInsightsService(
                peekabootActuatorService,
                actuatorResponseParser,
                healthMapper,
                runtimeMapper,
                dataSourceMapper,
                applicationMapper,
                environmentMapper,
                loggersMapper,
                flywayMapper,
                configMapper,
                scheduledTasksMapper,
                dataSourceMetadataList);
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
            QueryExtractor queryExtractor) {
        return new TraceInsightsService(traceStore.getIfAvailable(), traceTreeMapper, issueDetector, queryExtractor);
    }

    @Bean
    @ConditionalOnMissingBean
    public PeekabootController peekabootController(
            ActuatorInsightsService actuatorInsightsService,
            TraceInsightsService traceInsightsService,
            MetricsService metricsService,
            PeekabootProperties properties,
            UiTracingProperties uiTracingProperties,
            ObjectProvider<PeekabootTracingProperties> tracingProperties,
            ObjectProvider<InsightsService> insightsService) {
        return new PeekabootController(
                actuatorInsightsService,
                traceInsightsService,
                metricsService,
                properties,
                uiTracingProperties,
                tracingProperties.getIfAvailable(),
                insightsService.getIfAvailable());
    }
}
