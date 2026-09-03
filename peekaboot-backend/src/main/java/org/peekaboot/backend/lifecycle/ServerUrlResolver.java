package org.peekaboot.backend.lifecycle;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.peekaboot.backend.config.PeekabootPaths;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

public class ServerUrlResolver {

    private static final String SPRINGDOC_MARKER_CLASS = "org.springdoc.core.properties.SwaggerUiConfigProperties";

    private static final String DEFAULT_SWAGGER_UI_PATH = "/swagger-ui.html";

    // Looked up by name rather than by type: resolving PeekabootWebConfig.class would load
    // WebMvcConfigurer, and spring-webmvc is optional - absent from a reactive application,
    // which still reaches this resolver.
    static final String DASHBOARD_CONFIG_BEAN_NAME = "peekabootWebConfig";

    private static final String DASHBOARD_PATH = PeekabootPaths.BASE_PATH + "/";

    private final Environment environment;

    private final BooleanSupplier swaggerUiOnClasspath;

    public ServerUrlResolver(Environment environment) {
        this(environment, () -> ClassUtils.isPresent(SPRINGDOC_MARKER_CLASS, ServerUrlResolver.class.getClassLoader()));
    }

    ServerUrlResolver(Environment environment, BooleanSupplier swaggerUiOnClasspath) {
        this.environment = environment;
        this.swaggerUiOnClasspath = swaggerUiOnClasspath;
    }

    public Optional<String> resolveServiceUrl(ApplicationReadyEvent event) {
        ApplicationContext context = event.getApplicationContext();
        if (!(context instanceof WebServerApplicationContext webContext)) {
            return Optional.empty();
        }
        WebServer webServer = webContext.getWebServer();
        if (webServer == null) {
            return Optional.empty();
        }
        return Optional.of(buildBaseUrl(webServer.getPort()));
    }

    public Optional<String> resolveSwaggerUiUrl(ApplicationReadyEvent event) {
        if (!swaggerUiOnClasspath.getAsBoolean()) {
            return Optional.empty();
        }
        return resolveServiceUrl(event).map(base -> base + swaggerUiPath());
    }

    public Optional<String> resolveDashboardUrl(ApplicationReadyEvent event) {
        if (!event.getApplicationContext().containsBean(DASHBOARD_CONFIG_BEAN_NAME)) {
            return Optional.empty();
        }
        return resolveServiceUrl(event).map(base -> base + DASHBOARD_PATH);
    }

    private String buildBaseUrl(int port) {
        String scheme = sslEnabled() ? "https" : "http";
        String host = resolveHost();
        String contextPath = normalizeContextPath(environment.getProperty("server.servlet.context-path", ""));
        return scheme + "://" + host + ":" + port + contextPath;
    }

    /**
     * Boot's own rule: TLS is on as soon as any {@code server.ssl.*} property is bound
     * (a key store, a bundle), unless {@code server.ssl.enabled} says otherwise.
     */
    private boolean sslEnabled() {
        return Ssl.isEnabled(
                Binder.get(environment).bind("server.ssl", Ssl.class).orElse(null));
    }

    // AvoidUsingHardCodedIP: the wildcard bind addresses are compared against, not connected
    // to - they must be rewritten to something browsable
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private String resolveHost() {
        String host = environment.getProperty("server.address");
        if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
            return "localhost";
        }
        return host;
    }

    private static String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return "";
        }
        String normalized = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String swaggerUiPath() {
        String configured = environment.getProperty("springdoc.swagger-ui.path");
        if (configured == null || configured.isBlank()) {
            return DEFAULT_SWAGGER_UI_PATH;
        }
        return configured.startsWith("/") ? configured : "/" + configured;
    }
}
