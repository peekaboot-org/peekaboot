package org.peekaboot.autoconfigure;

import jakarta.servlet.DispatcherType;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.errorpage.PeekabootErrorExceptionResolver;
import org.peekaboot.backend.errorpage.PeekabootErrorView;
import org.peekaboot.backend.errorpage.PeekabootErrorViewResolver;
import org.peekaboot.backend.security.CredentialCache;
import org.peekaboot.backend.security.CredentialsFile;
import org.peekaboot.backend.security.DashboardAuthenticationFilter;
import org.peekaboot.backend.security.DashboardCredentials;
import org.peekaboot.backend.security.DashboardCredentialsResolver;
import org.peekaboot.backend.security.SecurityPosture;
import org.peekaboot.backend.security.SecurityPostureListener;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * The guard's credentials, the filter that enforces them and the startup report that explains
 * both.
 *
 * <p>The posture listener is registered whenever Peekaboot is enabled, not only while the guard
 * is armed: an operator who switched it off on a deployment launch still gets told their own
 * security must cover {@code /peekaboot/**}.
 */
@AutoConfiguration(after = {PeekabootAutoConfiguration.class, PeekabootStorageAutoConfiguration.class})
@ConditionalOnPeekabootServlet
@EnableConfigurationProperties(PeekabootProperties.class)
public class PeekabootSecurityAutoConfiguration {

    private static final String CREDENTIALS_FILE_NAME = "security.properties";

    private static final String ERROR_ATTRIBUTES_CLASS = "org.springframework.boot.webmvc.error.ErrorAttributes";

    private static final ResolvableType GUARD_TYPE =
            ResolvableType.forClassWithGenerics(FilterRegistrationBean.class, DashboardAuthenticationFilter.class);

    @Bean
    @ConditionalOnMissingBean
    public SecurityPostureListener securityPostureListener(SecurityPosture securityPosture) {
        return new SecurityPostureListener(securityPosture);
    }

    /**
     * Unconditional, and armed only when the credentials, the credentials file and the guard
     * filter all actually resolved - so the posture can never claim the dashboard is protected
     * while nothing enforces it, and a {@code peekaboot.security.enabled} value
     * {@code @ConditionalOnBooleanProperty} does not recognize as true or false (anything but
     * those two words) still leaves exactly one posture bean rather than none.
     */
    @Bean
    @ConditionalOnMissingBean
    public SecurityPosture securityPosture(
            ObjectProvider<DashboardCredentials> credentials,
            ObjectProvider<CredentialsFile> credentialsFile,
            PeekabootPaths paths,
            PeekabootProperties properties,
            ListableBeanFactory beanFactory,
            Environment environment) {
        DashboardCredentials resolvedCredentials = credentials.getIfAvailable();
        CredentialsFile resolvedCredentialsFile = credentialsFile.getIfAvailable();
        boolean deploymentLaunch = deploymentLaunch(environment);
        boolean errorPageOnADeployment = deploymentLaunch && errorPageRegistered(beanFactory);
        if (resolvedCredentials != null && resolvedCredentialsFile != null && guardRegistered(beanFactory)) {
            return SecurityPosture.armed(
                    paths.dashboardPath(),
                    resolvedCredentials,
                    resolvedCredentialsFile.path().orElse(null),
                    properties.isDevToolbar(),
                    errorPageOnADeployment);
        }
        return deploymentLaunch
                ? SecurityPosture.disabledOnADeployment(paths.dashboardPath(), errorPageOnADeployment)
                : SecurityPosture.quiet();
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
            return directory
                    .file(CREDENTIALS_FILE_NAME)
                    .map(CredentialsFile::new)
                    .orElseGet(CredentialsFile::unpersisted);
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

        // the missing-bean check matches the deduced generic type, so the application's other
        // filter registrations never back this one off
        @Bean
        @ConditionalOnMissingBean
        FilterRegistrationBean<DashboardAuthenticationFilter> dashboardAuthenticationFilter(
                DashboardCredentials credentials, CredentialCache credentialCache, PeekabootPaths paths) {

            FilterRegistrationBean<DashboardAuthenticationFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new DashboardAuthenticationFilter(credentials, credentialCache));
            // under the DispatcherServlet's prefix, where spring.mvc.servlet.path moves the dashboard's controllers
            registration.addUrlPatterns(paths.dashboardPath() + "/*");
            // an application path that forwards or includes into the dashboard, or an async dispatch
            // resuming one, must not carry a request past the guard
            registration.setDispatcherTypes(
                    DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.INCLUDE, DispatcherType.ASYNC);
            // fixed, since the guard answers for the path whatever Spring Security's filter order is
            registration.setOrder(0);
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
     * Asked of the bean definitions rather than through an injected {@code ObjectProvider}: with
     * no guard registered, injection retries the lookup ignoring generics, and every other
     * {@code FilterRegistrationBean} in the application is a candidate on that pass - two of them
     * fail the context, one of them would arm the posture behind a filter that guards nothing.
     * This is the same match {@code @ConditionalOnMissingBean} makes on the guard itself, so an
     * application that replaced the registration still counts as guarded.
     */
    private static boolean guardRegistered(ListableBeanFactory beanFactory) {
        return beanFactory.getBeanNamesForType(GUARD_TYPE, true, false).length > 0;
    }

    /**
     * Asked of the bean definitions, like {@link #guardRegistered}, so the posture never warns
     * about a page nobody serves. The switch alone can be on while {@code ErrorPageAutoConfiguration}
     * backs the page off for an application's own {@code templates/error.html}. The page's types
     * need Spring MVC, so they are named only where its {@code ErrorAttributes} is present.
     */
    private static boolean errorPageRegistered(ListableBeanFactory beanFactory) {
        return ClassUtils.isPresent(ERROR_ATTRIBUTES_CLASS, PeekabootSecurityAutoConfiguration.class.getClassLoader())
                && Stream.of(
                                PeekabootErrorView.class,
                                PeekabootErrorViewResolver.class,
                                PeekabootErrorExceptionResolver.class)
                        .anyMatch(type -> beanFactory.getBeanNamesForType(type, true, false).length > 0);
    }

    /** Detected-only, so an explicit override of {@code peekaboot.security.enabled} does not hide what was detected. */
    private static boolean deploymentLaunch(Environment environment) {
        return environment.getProperty(PeekabootPropertyKeys.SECURITY_DEPLOYMENT_DETECTED, Boolean.class, false);
    }
}
