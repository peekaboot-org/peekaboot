package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootWebConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ServerUrlResolverTest {

    private static boolean springdocPresent() {
        return true;
    }

    private static boolean springdocAbsent() {
        return false;
    }

    @Test
    void resolveServiceUrl_defaultsToHttpLocalhost() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrl_usesHttpsWhenSslEnabled() {
        var environment = new MockEnvironment();
        environment.setProperty("server.ssl.enabled", "true");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8443));

        assertThat(url).contains("https://localhost:8443");
    }

    @Test
    void resolveServiceUrl_substitutesLocalhostForWildcardAddress() {
        var environment = new MockEnvironment();
        environment.setProperty("server.address", "0.0.0.0");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrl_usesConfiguredHostWhenNotWildcard() {
        var environment = new MockEnvironment();
        environment.setProperty("server.address", "api.example.com");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://api.example.com:8080");
    }

    @Test
    void resolveServiceUrl_appendsContextPathWithoutTrailingSlash() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/api/");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.webApplication(8080));

        assertThat(url).contains("http://localhost:8080/api");
    }

    @Test
    void resolveServiceUrl_emptyOptionalForNonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(ReadyEvents.nonWebApplication());

        assertThat(url).isEmpty();
    }

    @Test
    void resolveSwaggerUiUrl_emptyWhenSpringDocAbsent() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8080));

        assertThat(url).isEmpty();
    }

    @Test
    void resolveSwaggerUiUrl_usesDefaultPathWhenSpringDocPresent() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8083));

        assertThat(url).contains("http://localhost:8083/swagger-ui.html");
    }

    @Test
    void resolveSwaggerUiUrl_usesConfiguredSwaggerPath() {
        var environment = new MockEnvironment();
        environment.setProperty("springdoc.swagger-ui.path", "/api-docs/swagger");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8083));

        assertThat(url).contains("http://localhost:8083/api-docs/swagger");
    }

    @Test
    void resolveSwaggerUiUrl_includesContextPath() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/app");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.webApplication(8083));

        assertThat(url).contains("http://localhost:8083/app/swagger-ui.html");
    }

    @Test
    void resolveSwaggerUiUrl_emptyForNonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(ReadyEvents.nonWebApplication());

        assertThat(url).isEmpty();
    }

    @Test
    void resolveDashboardUrl_pointsAtTheDashboardWhenPeekabootServesIt() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(url).contains("http://localhost:8083/peekaboot/");
    }

    @Test
    void resolveDashboardUrl_includesContextPath() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/app");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(url).contains("http://localhost:8083/app/peekaboot/");
    }

    @Test
    void resolveDashboardUrl_emptyWhenPeekabootDoesNotServeTheDashboard() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.webApplication(8083));

        assertThat(url).isEmpty();
    }

    @Test
    void resolveDashboardUrl_emptyForNonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveDashboardUrl(ReadyEvents.nonWebApplication());

        assertThat(url).isEmpty();
    }

    // Guards the string lookup in resolveDashboardUrl: the bean cannot be looked up by type,
    // because loading PeekabootWebConfig would drag in WebMvcConfigurer, and spring-webmvc is
    // an optional dependency absent from a reactive application. Renaming the class without
    // renaming the constant would silently drop the dashboard line from the banner.
    @Test
    void dashboardConfigBeanName_isTheNameSpringGivesPeekabootWebConfig() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(PeekabootWebConfig.class);

            assertThat(context.containsBeanDefinition(ServerUrlResolver.DASHBOARD_CONFIG_BEAN_NAME))
                    .isTrue();
        }
    }
}
