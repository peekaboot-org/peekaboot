package org.peekaboot.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.context.ApplicationContext;
import org.springframework.core.SpringVersion;
import org.springframework.stereotype.Service;

@Service
public final class PeekabootActuatorService {

    private static final Logger log = LoggerFactory.getLogger(PeekabootActuatorService.class);

    /**
     * The only actuator endpoints the insights mappers consume.
     */
    private static final Set<String> INSIGHTS_ENDPOINTS =
            Set.of("health", "info", "env", "loggers", "flyway", "configprops", "scheduledtasks");

    private final WebEndpointDiscoverer discoverer;

    public PeekabootActuatorService(
            ApplicationContext context,
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
    }

    /**
     * Invokes each insights endpoint's root read operation, keyed by endpoint id, alongside
     * the Spring versions under {@code spring}. An endpoint that fails is logged and left
     * out, so one broken endpoint never hides the others.
     */
    public Map<String, Object> getInsightsData() {

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("spring", buildSpringInfo());

        OperationArgumentResolver namespaceResolver =
                OperationArgumentResolver.of(WebServerNamespace.class, () -> WebServerNamespace.SERVER);

        OperationArgumentResolver apiVersionResolver =
                OperationArgumentResolver.of(ApiVersion.class, () -> ApiVersion.LATEST);
        for (ExposableWebEndpoint endpoint : discoverer.getEndpoints()) {
            String key = endpoint.getEndpointId().toLowerCaseString();
            if (!INSIGHTS_ENDPOINTS.contains(key)) {
                continue;
            }
            endpoint.getOperations().stream()
                    .filter(op -> op.getType() == OperationType.READ)
                    .filter(op -> op.getRequestPredicate().getPath().equals(endpoint.getRootPath()))
                    .findFirst()
                    .ifPresent(op -> {
                        try {
                            Object result = op.invoke(new InvocationContext(
                                    SecurityContext.NONE, Map.of(), namespaceResolver, apiVersionResolver));
                            results.put(key, result);
                        } catch (Exception e) {
                            log.warn("Actuator endpoint '{}' failed: {}", key, e.toString());
                        }
                    });
        }

        return results;
    }

    private Map<String, String> buildSpringInfo() {

        Map<String, String> springInfo = new LinkedHashMap<>();
        springInfo.put("bootVersion", SpringBootVersion.getVersion());
        springInfo.put("frameworkVersion", SpringVersion.getVersion());
        return springInfo;
    }
}
