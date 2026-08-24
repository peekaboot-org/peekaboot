package org.peekaboot.backend.lifecycle;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

public class ServerUrlResolver {

    private static final String SPRINGDOC_MARKER_CLASS = "org.springdoc.core.properties.SwaggerUiConfigProperties";

    private static final String DEFAULT_SWAGGER_UI_PATH = "/swagger-ui.html";

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


    private String buildBaseUrl(int port) {
        String scheme = environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http";
        String host = resolveHost();
        String contextPath = normalizeContextPath(environment.getProperty("server.servlet.context-path", ""));
        return scheme + "://" + host + ":" + port + contextPath;
    }


    // AvoidUsingHardCodedIP: "0.0.0.0" is compared against, not connected to - it is
    // the wildcard bind address that must be rewritten to something browsable
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private String resolveHost() {
        String host = environment.getProperty("server.address");
        if (host == null || host.isBlank() || "0.0.0.0".equals(host)) {
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
