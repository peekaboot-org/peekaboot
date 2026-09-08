package org.peekaboot.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.peekaboot.backend.actuator.InsightsSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.actuate.endpoint.Show;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.actuate.scheduling.ScheduledTasksEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.flyway.actuate.endpoint.FlywayEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.logging.LoggerGroups;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.SpringVersion;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The actuator readings the dashboard consumes, one {@link InsightsSource} bean each.
 *
 * <p>Peekaboot constructs the endpoints itself rather than discovering the application's,
 * which is what makes the dashboard independent of {@code management.endpoint.*}: no
 * exposure or access setting decides whether a reading exists, and {@code env} and
 * {@code configprops} are built with {@link Show#ALWAYS} so no {@code show-values} setting
 * decides whether their values arrive real or as {@code ******}. What the dashboard then
 * shows is {@code MaskingEngine}'s decision alone. Enabling Peekaboot correspondingly
 * changes nothing about the application's own {@code /actuator/*}.
 *
 * <p>The endpoint objects are deliberately not beans: a second bean carrying
 * {@code @Endpoint(id = "env")} makes the application's own {@code EndpointDiscoverer} fail
 * at startup with a duplicate-id error. They are built inside the supplier, per read, which
 * also keeps every {@link ObjectProvider} lookup at read time - so this auto-configuration
 * needs no ordering relationship with Boot's endpoint auto-configurations and no
 * {@code @ConditionalOnBean} guards.
 *
 * <p>Each bean is {@link ConditionalOnMissingBean @ConditionalOnMissingBean} by name rather
 * than by type: they share one type, so a by-type condition would let an application that
 * replaces a single source suppress all of them.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HealthEndpoint.class, InfoEndpoint.class})
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
public class ActuatorSourcesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "springVersionsInsightsSource")
    public InsightsSource springVersionsInsightsSource() {
        return new InsightsSource("spring", () -> {
            // LinkedHashMap, not Map.of: either version is null when the jar carries no
            // implementation version, which Map.of rejects.
            Map<String, String> versions = new LinkedHashMap<>();
            versions.put("bootVersion", SpringBootVersion.getVersion());
            versions.put("frameworkVersion", SpringVersion.getVersion());
            return versions;
        });
    }

    /**
     * The application's own {@link HealthEndpoint} bean, not one Peekaboot builds: it has no
     * value-visibility gate, and {@link HealthEndpoint#health()} already ignores
     * {@code management.endpoint.health.show-details}, which belongs to the application's
     * public {@code /actuator/health}. Building one would mean duplicating the contributor
     * registry and the group configuration for nothing.
     */
    @Bean
    @ConditionalOnMissingBean(name = "healthInsightsSource")
    public InsightsSource healthInsightsSource(ObjectProvider<HealthEndpoint> healthEndpoint) {
        return new InsightsSource("health", () -> {
            HealthEndpoint endpoint = healthEndpoint.getIfAvailable();
            return endpoint == null ? null : endpoint.health();
        });
    }

    @Bean
    @ConditionalOnMissingBean(name = "infoInsightsSource")
    public InsightsSource infoInsightsSource(ObjectProvider<InfoContributor> infoContributors) {
        return new InsightsSource(
                "info", () -> new InfoEndpoint(infoContributors.orderedStream().toList()).info());
    }

    @Bean
    @ConditionalOnMissingBean(name = "envInsightsSource")
    public InsightsSource envInsightsSource(
            Environment environment, ObjectProvider<SanitizingFunction> sanitizingFunctions) {
        return new InsightsSource(
                "env",
                () -> new EnvironmentEndpoint(
                                environment, sanitizingFunctions.orderedStream().toList(), Show.ALWAYS)
                        .environment(null));
    }

    @Bean
    @ConditionalOnMissingBean(name = "configpropsInsightsSource")
    public InsightsSource configpropsInsightsSource(
            ApplicationContext context, ObjectProvider<SanitizingFunction> sanitizingFunctions) {
        return new InsightsSource("configprops", () -> {
            ConfigurationPropertiesReportEndpoint endpoint = new ConfigurationPropertiesReportEndpoint(
                    sanitizingFunctions.orderedStream().toList(), Show.ALWAYS);
            endpoint.setApplicationContext(context);
            return endpoint.configurationProperties();
        });
    }

    @Bean
    @ConditionalOnMissingBean(name = "loggersInsightsSource")
    public InsightsSource loggersInsightsSource(
            ObjectProvider<LoggingSystem> loggingSystem, ObjectProvider<LoggerGroups> loggerGroups) {
        return new InsightsSource("loggers", () -> {
            LoggingSystem system = loggingSystem.getIfAvailable();
            return system == null
                    ? null
                    : new LoggersEndpoint(system, loggerGroups.getIfAvailable(LoggerGroups::new)).loggers();
        });
    }

    @Bean
    @ConditionalOnMissingBean(name = "scheduledTasksInsightsSource")
    public InsightsSource scheduledTasksInsightsSource(ObjectProvider<ScheduledTaskHolder> holders) {
        return new InsightsSource(
                "scheduledtasks",
                () -> new ScheduledTasksEndpoint(holders.orderedStream().toList()).scheduledTasks());
    }

    /**
     * Nested so {@link FlywayEndpoint}, which moved to the {@code spring-boot-flyway} module
     * in Boot 4, is only loaded where Flyway is on the classpath. The Flyway-bean check is
     * the supplier's rather than the condition's, keeping the source absent - not empty -
     * for an application that has Flyway on the classpath but no migrations configured.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Flyway.class, FlywayEndpoint.class})
    static class FlywaySourceConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "flywayInsightsSource")
        InsightsSource flywayInsightsSource(ApplicationContext context, ObjectProvider<Flyway> flyway) {
            return new InsightsSource(
                    "flyway",
                    () -> flyway.stream().findAny().isEmpty() ? null : new FlywayEndpoint(context).flywayBeans());
        }
    }
}
