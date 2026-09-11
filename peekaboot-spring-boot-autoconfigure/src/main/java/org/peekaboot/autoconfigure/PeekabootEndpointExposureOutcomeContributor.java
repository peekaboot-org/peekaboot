package org.peekaboot.autoconfigure;

import java.util.Set;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.EndpointExposureOutcomeContributor;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.EndpointExposure;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.core.env.Environment;

/**
 * Reports the health endpoint as exposed while peekaboot is enabled, so
 * {@code @ConditionalOnAvailableEndpoint} creates its bean without requiring
 * {@code management.endpoints.web.exposure.include}. The {@code /actuator} HTTP mapping
 * applies {@code management.endpoints.web.exposure} on its own, so this contributor
 * changes nothing about HTTP reachability.
 */
public class PeekabootEndpointExposureOutcomeContributor implements EndpointExposureOutcomeContributor {

    /**
     * Health is the one endpoint Peekaboot borrows rather than builds - it carries no
     * value-visibility gate of its own and duplicating the contributor registry would buy
     * nothing - so its bean has to exist even where the application exposes nothing over
     * the web. Every other endpoint Peekaboot reads it constructs itself, so nothing else
     * needs forcing here. {@code management.endpoint.health.access=none} still removes the
     * bean: access is resolved before exposure contributors run.
     */
    private static final EndpointId HEALTH_ENDPOINT_ID = EndpointId.of("health");

    private final Environment environment;

    public PeekabootEndpointExposureOutcomeContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public ConditionOutcome getExposureOutcome(
            EndpointId endpointId, Set<EndpointExposure> exposures, ConditionMessage.Builder message) {
        if (!HEALTH_ENDPOINT_ID.equals(endpointId)) {
            return null;
        }
        if (!exposures.contains(EndpointExposure.WEB)) {
            return null;
        }
        if (!environment.getProperty(PeekabootPropertyKeys.ENABLED, Boolean.class, false)) {
            return null;
        }
        return ConditionOutcome.match(message.because("peekaboot is enabled and reads the health endpoint in-process"));
    }
}
