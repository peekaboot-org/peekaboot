package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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

        Optional<String> url = resolver.resolveServiceUrl(webEvent(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrl_usesHttpsWhenSslEnabled() {
        var environment = new MockEnvironment();
        environment.setProperty("server.ssl.enabled", "true");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(webEvent(8443));

        assertThat(url).contains("https://localhost:8443");
    }

    @Test
    void resolveServiceUrl_substitutesLocalhostForWildcardAddress() {
        var environment = new MockEnvironment();
        environment.setProperty("server.address", "0.0.0.0");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(webEvent(8080));

        assertThat(url).contains("http://localhost:8080");
    }

    @Test
    void resolveServiceUrl_usesConfiguredHostWhenNotWildcard() {
        var environment = new MockEnvironment();
        environment.setProperty("server.address", "api.example.com");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(webEvent(8080));

        assertThat(url).contains("http://api.example.com:8080");
    }

    @Test
    void resolveServiceUrl_appendsContextPathWithoutTrailingSlash() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/api/");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveServiceUrl(webEvent(8080));

        assertThat(url).contains("http://localhost:8080/api");
    }

    @Test
    void resolveServiceUrl_emptyOptionalForNonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        var event = mock(ApplicationReadyEvent.class);
        when(event.getApplicationContext()).thenReturn(mock(ConfigurableApplicationContext.class));

        Optional<String> url = resolver.resolveServiceUrl(event);

        assertThat(url).isEmpty();
    }

    @Test
    void resolveSwaggerUiUrl_emptyWhenSpringDocAbsent() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocAbsent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(webEvent(8080));

        assertThat(url).isEmpty();
    }

    @Test
    void resolveSwaggerUiUrl_usesDefaultPathWhenSpringDocPresent() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(webEvent(8083));

        assertThat(url).contains("http://localhost:8083/swagger-ui.html");
    }

    @Test
    void resolveSwaggerUiUrl_usesConfiguredSwaggerPath() {
        var environment = new MockEnvironment();
        environment.setProperty("springdoc.swagger-ui.path", "/api-docs/swagger");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(webEvent(8083));

        assertThat(url).contains("http://localhost:8083/api-docs/swagger");
    }

    @Test
    void resolveSwaggerUiUrl_includesContextPath() {
        var environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/app");
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        Optional<String> url = resolver.resolveSwaggerUiUrl(webEvent(8083));

        assertThat(url).contains("http://localhost:8083/app/swagger-ui.html");
    }

    @Test
    void resolveSwaggerUiUrl_emptyForNonWebContext() {
        var environment = new MockEnvironment();
        var resolver = new ServerUrlResolver(environment, ServerUrlResolverTest::springdocPresent);

        var event = mock(ApplicationReadyEvent.class);
        when(event.getApplicationContext()).thenReturn(mock(ConfigurableApplicationContext.class));

        Optional<String> url = resolver.resolveSwaggerUiUrl(event);

        assertThat(url).isEmpty();
    }

    private static ApplicationReadyEvent webEvent(int port) {
        var event = mock(ApplicationReadyEvent.class);
        var ctx = mock(ConfigurableWebServerApplicationContext.class);
        var server = mock(WebServer.class);
        when(event.getApplicationContext()).thenReturn(ctx);
        when(ctx.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(port);
        return event;
    }
}
