package org.peekaboot.autoconfigure;

import org.peekaboot.backend.health.HealthStatusCodes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The code the health report answers a status with is the primary health group's
 * {@link HttpCodeStatusMapper}, so {@code management.endpoint.health.status.http-mapping} and an
 * application's own mapper bean reach it. Where the application has no health endpoint, Boot's
 * defaults apply.
 */
@Configuration(proxyBeanMethods = false)
class HealthStatusCodesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HealthStatusCodes peekabootHealthStatusCodes(ObjectProvider<HealthEndpointGroups> groups) {
        HealthEndpointGroups available = groups.getIfAvailable();
        HttpCodeStatusMapper mapper = available == null
                ? HttpCodeStatusMapper.getDefault()
                : available.getPrimary().getHttpCodeStatusMapper();
        return status -> mapper.getStatusCode(new Status(status));
    }
}
