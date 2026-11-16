package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.config.PeekabootWebConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ServerUrlResolverTest {

    private static final ObjectProvider<PeekabootPaths> AT_THE_ROOT = pathsBean(PeekabootPaths.defaults());

    /** A reactive application: Peekaboot registers no {@link PeekabootPaths} there. */
    private static final ObjectProvider<PeekabootPaths> NO_PATHS_BEAN =
            new StaticListableBeanFactory().getBeanProvider(PeekabootPaths.class);

    private static ObjectProvider<PeekabootPaths> pathsBean(PeekabootPaths paths) {
        var beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("peekabootPaths", paths);
        return beanFactory.getBeanProvider(PeekabootPaths.class);
    }

    private static ObjectProvider<PeekabootPaths> behindDispatcherServletAt(String prefix) {
        return pathsBean(new PeekabootPaths("/actuator", "/ctx", prefix));
    }

    private static boolean springdocPresent() {
        return true;
    }

    private static boolean springdocAbsent() {
        return false;
    }

    @Test
    void resolveServiceUrlDefaultsToHttpLocalhost() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrlUsesHttpsWhenSslEnabled() {
        var environment = new MockEnvironment();
        environment.setProperty("server.ssl.enabled", "true");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8443));

        assertThat(url).contains("https://localhost:8443");
    }

    /** Spring enables TLS when any server.ssl.* property is bound; server.ssl.enabled defaults to true then. */
    @Test
    void resolveServiceUrlUsesHttpsWhenAKeyStoreIsBoundWithoutAnExplicitEnabledFlag() {
        var environment = new MockEnvironment();
        environment.setProperty("server.ssl.key-store", "classpath:keystore.p12");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8443));

        assertThat(url).contains("https://localhost:8443");
    }

    @Test
    void resolveServiceUrlStaysHttpWhenSslIsExplicitlyDisabledDespiteAKeyStore() {
        var environment = new MockEnvironment();
        environment.setProperty("server.ssl.key-store", "classpath:keystore.p12");
        environment.setProperty("server.ssl.enabled", "false");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrlSubstitutesLocalhostForTheIpv6WildcardAddress() {
        var environment = new MockEnvironment();
        environment.setProperty("server.address", "::");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrlSubstitutesLocalhostForWildcardAddress() {
        var environment = new MockEnvironment();
        environment.setProperty("server.address", "0.0.0.0");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrlUsesConfiguredHostWhenNotWildcard() {
        var environment = new MockEnvironment();
        environment.setProperty("server.address", "api.example.com");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://api.example.com:8080");
    }

    @Test
    void resolveServiceUrlAppendsContextPathWithoutTrailingSlash() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/api/");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080/api");
    }

    @Test
    void resolveServiceUrlIsEmptyForANonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.nonWebApplication());

        assertThat(url).isEmpty();
    }

    @Test
    void resolveSwaggerUiUrlIsEmptyWhenSpringDocIsAbsent() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8080));

        assertThat(url).isEmpty();
    }

    @Test
    void resolveSwaggerUiUrlUsesDefaultPathWhenSpringDocPresent() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8083));

        assertThat(url).contains("http://localhost:8083/swagger-ui.html");
    }

    @Test
    void resolveSwaggerUiUrlUsesConfiguredSwaggerPath() {
        var environment = new MockEnvironment();
        environment.setProperty("springdoc.swagger-ui.path", "/api-docs/swagger");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8083));

        assertThat(url).contains("http://localhost:8083/api-docs/swagger");
    }

    @Test
    void resolveSwaggerUiUrlIncludesContextPath() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/app");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8083));

        assertThat(url).contains("http://localhost:8083/app/swagger-ui.html");
    }

    /** springdoc maps its UI as a controller, so it sits behind the DispatcherServlet's prefix too. */
    @Test
    void resolveSwaggerUiUrlIncludesTheDispatcherServletPrefix() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/ctx");
        var resolver = new ServerUrlResolver(
                environment, behindDispatcherServletAt("/app"), ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8083));

        assertThat(url).contains("http://localhost:8083/ctx/app/swagger-ui.html");
    }

    @Test
    void resolveSwaggerUiUrlIsEmptyForANonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.nonWebApplication());

        assertThat(url).isEmpty();
    }

    @Test
    void resolveDashboardUrlPointsAtTheDashboardWhenPeekabootServesIt() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(url).contains("http://localhost:8083/peekaboot/");
    }

    @Test
    void resolveDashboardUrlIncludesContextPath() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/app");
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(url).contains("http://localhost:8083/app/peekaboot/");
    }

    @Test
    void resolveDashboardUrlIncludesTheDispatcherServletPrefix() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/ctx");
        var resolver = new ServerUrlResolver(
                environment, behindDispatcherServletAt("/app"), ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(url).contains("http://localhost:8083/ctx/app/peekaboot/");
    }

    @Test
    void resolveDashboardUrlFallsBackToTheRootWithoutAPathsBean() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, NO_PATHS_BEAN, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(url).contains("http://localhost:8083/peekaboot/");
    }

    @Test
    void resolveDashboardUrlIsEmptyWhenPeekabootDoesNotServeTheDashboard() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplication(8083));

        assertThat(url).isEmpty();
    }

    @Test
    void resolveDashboardUrlIsEmptyForANonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, AT_THE_ROOT, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.nonWebApplication());

        assertThat(url).isEmpty();
    }

    // Guards the string lookup in resolveDashboardUrl: the bean cannot be looked up by type,
    // because loading PeekabootWebConfig would drag in WebMvcConfigurer, and spring-webmvc is
    // an optional dependency absent from a reactive application. Renaming the class without
    // renaming the constant would silently drop the dashboard line from the banner.
    @Test
    void dashboardConfigBeanNameIsTheNameSpringGivesPeekabootWebConfig() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(PeekabootWebConfig.class);

            assertThat(context.containsBeanDefinition(ServerUrlResolver.DASHBOARD_CONFIG_BEAN_NAME))
                    .isTrue();
        }
    }
}
