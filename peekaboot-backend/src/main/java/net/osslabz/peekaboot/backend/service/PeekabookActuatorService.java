package net.osslabz.peekaboot.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
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


public class PeekabookActuatorService {


    private final WebEndpointDiscoverer discoverer;


    public PeekabookActuatorService(
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
            List.of(),  // Empty endpoint filters = no exposure filtering
            List.of()   // Empty operation filters
        );
    }


    public Map<String, Object> getData() {

        Map<String, Object> results = new LinkedHashMap<>();

        OperationArgumentResolver namespaceResolver = OperationArgumentResolver.of(
            WebServerNamespace.class,
            () -> WebServerNamespace.SERVER
        );

        OperationArgumentResolver apiVersionResolver = OperationArgumentResolver.of(
            ApiVersion.class,
            () -> ApiVersion.LATEST
        );
        for (ExposableWebEndpoint endpoint : discoverer.getEndpoints()) {
            endpoint.getOperations().stream()
                .filter(op -> op.getType() == OperationType.READ)
                .filter(op -> op.getRequestPredicate().getPath().equals(endpoint.getRootPath()))
                .findFirst()
                .ifPresent(op -> {
                    String key = endpoint.getEndpointId().toLowerCaseString();
                    try {
                        Object result = op.invoke(new InvocationContext(SecurityContext.NONE, Map.of(), namespaceResolver, apiVersionResolver));
                        results.put(key, result);
                    } catch (Exception e) {
                        results.put(key, "Error: " + e.getMessage());
                    }
                });
        }

        return results;
    }
}