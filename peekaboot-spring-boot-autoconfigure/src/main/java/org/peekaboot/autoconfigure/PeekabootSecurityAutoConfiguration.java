package org.peekaboot.autoconfigure;

import java.nio.file.Path;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.security.CredentialCache;
import org.peekaboot.backend.security.CredentialsFile;
import org.peekaboot.backend.security.DashboardAuthenticationFilter;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.backend.security.DashboardCredentialsResolver;
import org.peekaboot.backend.security.NeverAuthenticated;
import org.peekaboot.backend.security.RequestAuthentication;
import org.peekaboot.backend.security.SecurityContextRequestAuthentication;
import org.peekaboot.backend.security.SecurityPosture;
import org.peekaboot.backend.security.SecurityPostureListener;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * The credentials Peekaboot falls back to, the filter that enforces them and the startup report
 * that explains both.
 *
 * <p>The posture listener is registered whenever Peekaboot is enabled, not only while the
 * fallback is armed: an operator who switched the fallback off on a deployment launch still gets
 * told the dashboard is unauthenticated.
 */
@AutoConfiguration(after = {PeekabootAutoConfiguration.class, PeekabootStorageAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@EnableConfigurationProperties(PeekabootProperties.class)
public class PeekabootSecurityAutoConfiguration {

    private static final String SECURITY_CONTEXT_HOLDER =
            "org.springframework.security.core.context.SecurityContextHolder";

    private static final String CREDENTIALS_FILE_NAME = "security.properties";

    private static final int GUARD_ORDER_MARGIN = 100;

    /** Spring Boot's own default for {@code spring.security.filter.order}. */
    private static final int DEFAULT_SECURITY_FILTER_ORDER = -100;

    @Bean
    @ConditionalOnMissingBean
    public SecurityPostureListener securityPostureListener(SecurityPosture securityPosture) {
        return new SecurityPostureListener(securityPosture);
    }

    /** Armed: the posture reports what the guard below is doing. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.SECURITY_ENABLED)
    public SecurityPosture armedSecurityPosture(
            DashboardCredentials credentials, CredentialsFile credentialsFile, Environment environment) {
        return SecurityPosture.armed(
                credentials,
                credentialsFile.path(),
                springSecurityPresent(),
                environment.getProperty(PeekabootPropertyKeys.DEV_TOOLBAR, Boolean.class, false));
    }

    /**
     * Not armed. {@code matchIfMissing} covers a context where the property is absent
     * entirely - the detection post-processor always sets it in a real application, but a
     * bare test context does not, and exactly one posture bean must exist either way.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(
            name = PeekabootPropertyKeys.SECURITY_ENABLED,
            havingValue = false,
            matchIfMissing = true)
    public SecurityPosture disarmedSecurityPosture(Environment environment) {
        return deploymentLaunch(environment) ? SecurityPosture.disabledOnADeployment() : SecurityPosture.quiet();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.SECURITY_ENABLED)
    static class GuardConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CredentialsFile peekabootCredentialsFile(
                PeekabootProperties properties, ObjectProvider<StorageDirectory> storageDirectory) {
            String configured = properties.getSecurity().getCredentialsFile();
            if (StringUtils.hasText(configured)) {
                return new CredentialsFile(Path.of(configured.trim()));
            }
            StorageDirectory directory = storageDirectory.getObject();
            return new CredentialsFile(directory.root().resolve(CREDENTIALS_FILE_NAME));
        }

        @Bean
        @ConditionalOnMissingBean
        DashboardCredentials dashboardCredentials(
                PeekabootProperties properties,
                CredentialsFile credentialsFile,
                ObjectProvider<BuildProperties> buildProperties,
                Environment environment) {
            return new DashboardCredentialsResolver(
                            properties.getSecurity(), credentialsFile, applicationName(buildProperties, environment))
                    .resolve();
        }

        @Bean
        @ConditionalOnMissingBean
        CredentialCache credentialCache() {
            return new CredentialCache();
        }

        @Bean
        @ConditionalOnMissingBean
        RequestAuthentication requestAuthentication() {
            return springSecurityPresent() ? new SecurityContextRequestAuthentication() : new NeverAuthenticated();
        }

        // the missing-bean check matches the deduced generic type, so the application's other
        // filter registrations never back this one off
        @Bean
        @ConditionalOnMissingBean
        FilterRegistrationBean<DashboardAuthenticationFilter> dashboardAuthenticationFilter(
                DashboardCredentials credentials,
                CredentialCache credentialCache,
                RequestAuthentication requestAuthentication,
                Environment environment) {

            FilterRegistrationBean<DashboardAuthenticationFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(
                    new DashboardAuthenticationFilter(credentials, credentialCache, requestAuthentication));
            registration.addUrlPatterns(PeekabootPaths.BASE_PATH + "/*");
            // after Spring Security's own filter wherever the application has put it, so the
            // guard sees only requests that chain already let through
            registration.setOrder(environment.getProperty(
                            "spring.security.filter.order", Integer.class, DEFAULT_SECURITY_FILTER_ORDER)
                    + GUARD_ORDER_MARGIN);
            registration.setName("dashboardAuthenticationFilter");
            return registration;
        }

        /** The artifact alone: {@code com.acme.orders-admin} is a worse username than {@code orders-admin}. */
        private static String applicationName(
                ObjectProvider<BuildProperties> buildProperties, Environment environment) {
            BuildProperties build = buildProperties.getIfAvailable();
            if (build != null && StringUtils.hasText(build.getArtifact())) {
                return build.getArtifact();
            }
            return environment.getProperty("spring.application.name");
        }
    }

    /**
     * The thread's context classloader, not this class's own: the two differ under a test
     * runner's {@code FilteredClassLoader}, and only the context one reflects it.
     */
    static boolean springSecurityPresent() {
        return ClassUtils.isPresent(
                SECURITY_CONTEXT_HOLDER, Thread.currentThread().getContextClassLoader());
    }

    /** Reads the detection source directly, so an explicit override does not hide what was detected. */
    private static boolean deploymentLaunch(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return false;
        }
        PropertySource<?> detection =
                configurable.getPropertySources().get(PeekabootPropertyKeys.DETECTION_PROPERTY_SOURCE_NAME);
        return detection != null && Boolean.TRUE.equals(detection.getProperty(PeekabootPropertyKeys.SECURITY_ENABLED));
    }
}
