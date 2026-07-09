package net.osslabz.peekaboot.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.osslabz.peekaboot.backend.actuator.raw.ActuatorRawResponse;
import net.osslabz.peekaboot.backend.lifecycle.DataSourceMetadata;
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
public class PeekabookActuatorService {

    /**
     * Endpoints whose READ operation is expensive or dangerous to invoke on
     * every dashboard load (full JVM thread dump, heap dump file, log file).
     */
    private static final Set<String> EXPENSIVE_ENDPOINTS = Set.of("heapdump", "threaddump", "logfile");

    /**
     * The only actuator endpoints the insights mappers consume.
     */
    private static final Set<String> INSIGHTS_ENDPOINTS =
        Set.of("health", "info", "env", "loggers", "flyway", "configprops", "scheduledtasks");

    private final WebEndpointDiscoverer discoverer;

    private final List<DataSourceMetadata> dataSourceMetadataList;


    public PeekabookActuatorService(
        ApplicationContext context,
        ParameterValueMapper parameterMapper,
        EndpointMediaTypes mediaTypes,
        ObjectProvider<PathMapper> pathMappers,
        ObjectProvider<AdditionalPathsMapper> additionalPathsMappers,
        ObjectProvider<OperationInvokerAdvisor> advisors,
        ObjectProvider<List<DataSourceMetadata>> dataSourceMetadataListProvider) {

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
        this.dataSourceMetadataList = dataSourceMetadataListProvider.getIfAvailable(List::of);
    }


    public Map<String, Object> getRawData() {
        return collectData(key -> !EXPENSIVE_ENDPOINTS.contains(key));
    }

    /**
     * Invokes only the actuator endpoints the insights mappers consume.
     */
    public Map<String, Object> getInsightsData() {
        return collectData(INSIGHTS_ENDPOINTS::contains);
    }

    private Map<String, Object> collectData(Predicate<String> endpointKeyFilter) {

        Map<String, Object> results = new LinkedHashMap<>();

        // Add Spring version info
        results.put("spring", buildSpringInfo());

        // Add DataSource metadata
        results.put("dataSources", buildDataSourcesInfo());

        OperationArgumentResolver namespaceResolver = OperationArgumentResolver.of(
            WebServerNamespace.class,
            () -> WebServerNamespace.SERVER
        );

        OperationArgumentResolver apiVersionResolver = OperationArgumentResolver.of(
            ApiVersion.class,
            () -> ApiVersion.LATEST
        );
        for (ExposableWebEndpoint endpoint : discoverer.getEndpoints()) {
            String key = endpoint.getEndpointId().toLowerCaseString();
            if (!endpointKeyFilter.test(key)) {
                continue;
            }
            endpoint.getOperations().stream()
                .filter(op -> op.getType() == OperationType.READ)
                .filter(op -> op.getRequestPredicate().getPath().equals(endpoint.getRootPath()))
                .findFirst()
                .ifPresent(op -> {
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

    public ActuatorRawResponse getData() {
        return ActuatorRawResponse.wrap(getRawData());
    }


    private Map<String, String> buildSpringInfo() {

        Map<String, String> springInfo = new LinkedHashMap<>();
        springInfo.put("bootVersion", SpringBootVersion.getVersion());
        springInfo.put("frameworkVersion", SpringVersion.getVersion());
        return springInfo;
    }


    private List<Map<String, Object>> buildDataSourcesInfo() {

        List<Map<String, Object>> dataSources = new ArrayList<>();

        for (DataSourceMetadata metadata : dataSourceMetadataList) {
            if (metadata == null) continue;

            Map<String, Object> ds = new LinkedHashMap<>();
            ds.put("name", metadata.getDataSourceName());
            ds.put("databaseName", metadata.getDatabaseName());
            ds.put("username", metadata.getUsername());

            // Format hosts
            List<String> hosts = new ArrayList<>();
            if (metadata.getHosts() != null) {
                metadata.getHosts().forEach(host -> hosts.add(host.toString()));
            }
            ds.put("hosts", hosts);

            // Database product info
            ds.put("productName", metadata.getDatabaseProductName());
            ds.put("productVersion", metadata.getDatabaseProductVersion());

            // Driver info
            ds.put("driverName", metadata.getDriverName());
            ds.put("driverVersion", metadata.getDriverVersion());

            // Connection params
            Map<String, Object> params = new LinkedHashMap<>();
            if (metadata.getConnectionParams() != null) {
                metadata.getConnectionParams().forEach((key, prop) -> {
                    Map<String, Object> paramInfo = new LinkedHashMap<>();
                    paramInfo.put("value", prop.value());
                    paramInfo.put("source", prop.source() != null ? prop.source().name() : null);
                    params.put(key, paramInfo);
                });
            }
            ds.put("connectionParams", params);

            dataSources.add(ds);
        }

        return dataSources;
    }
}