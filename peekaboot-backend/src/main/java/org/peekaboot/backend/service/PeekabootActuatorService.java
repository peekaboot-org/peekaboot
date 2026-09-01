package org.peekaboot.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.OperationArgumentResolver;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.ApplicationContext;
import org.springframework.core.SpringVersion;
import org.springframework.stereotype.Service;

@Service
public final class PeekabootActuatorService {

    private static final Logger log = LoggerFactory.getLogger(PeekabootActuatorService.class);

    private static final String HEALTH_KEY = "health";

    /**
     * The actuator endpoints the insights mappers consume through their web READ
     * operation. Health is not among them: its web operation applies
     * {@code management.endpoint.health.show-details}, which is the application's own
     * choice for its public {@code /actuator/health}, so the dashboard reads the
     * {@link HealthEndpoint} bean directly instead - {@link HealthEndpoint#health()} always
     * carries the components.
     */
    private static final Set<String> DISCOVERED_INSIGHTS_ENDPOINTS =
            Set.of("info", "env", "loggers", "flyway", "configprops", "scheduledtasks");

    private final WebEndpointDiscoverer discoverer;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;

    public PeekabootActuatorService(
            ApplicationContext context,
            ObjectProvider<HealthEndpoint> healthEndpoint,
            ParameterValueMapper parameterMapper,
            EndpointMediaTypes mediaTypes,
            ObjectProvider<PathMapper> pathMappers,
            ObjectProvider<AdditionalPathsMapper> additionalPathsMappers,
            ObjectProvider<OperationInvokerAdvisor> advisors) {

        this.discoverer = new WebEndpointDiscoverer(
                context,
                parameterMapper,
                mediaTypes,
                pathMappers.orderedStream().toList(),
                additionalPathsMappers.orderedStream().toList(),
                advisors.orderedStream().toList(),
                List.of(), // Empty endpoint filters = no exposure filtering
                List.of() // Empty operation filters
                );
        this.healthEndpoint = healthEndpoint;
    }

    /**
     * Invokes each insights endpoint's root read operation, keyed by endpoint id, alongside
     * the Spring versions under {@code spring}. An endpoint that fails is logged and left
     * out, so one broken endpoint never hides the others.
     */
    public Map<String, Object> getInsightsData() {

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("spring", buildSpringInfo());

        healthEndpoint.ifAvailable(endpoint -> invoke(HEALTH_KEY, endpoint::health, results));

        OperationArgumentResolver namespaceResolver =
                OperationArgumentResolver.of(WebServerNamespace.class, () -> WebServerNamespace.SERVER);

        OperationArgumentResolver apiVersionResolver =
                OperationArgumentResolver.of(ApiVersion.class, () -> ApiVersion.LATEST);
        for (ExposableWebEndpoint endpoint : discoverer.getEndpoints()) {
            String key = endpoint.getEndpointId().toLowerCaseString();
            if (!DISCOVERED_INSIGHTS_ENDPOINTS.contains(key)) {
                continue;
            }
            endpoint.getOperations().stream()
                    .filter(op -> op.getType() == OperationType.READ)
                    .filter(op -> op.getRequestPredicate().getPath().equals(endpoint.getRootPath()))
                    .findFirst()
                    .ifPresent(op -> invoke(
                            key,
                            () -> op.invoke(new InvocationContext(
                                    SecurityContext.NONE, Map.of(), namespaceResolver, apiVersionResolver)),
                            results));
        }

        return results;
    }

    private static void invoke(String key, Supplier<Object> operation, Map<String, Object> results) {
        try {
            results.put(key, operation.get());
        } catch (Exception e) {
            log.warn("Actuator endpoint '{}' failed: {}", key, e.toString());
        }
    }

    private Map<String, String> buildSpringInfo() {

        Map<String, String> springInfo = new LinkedHashMap<>();
        springInfo.put("bootVersion", SpringBootVersion.getVersion());
        springInfo.put("frameworkVersion", SpringVersion.getVersion());
        return springInfo;
    }
}
