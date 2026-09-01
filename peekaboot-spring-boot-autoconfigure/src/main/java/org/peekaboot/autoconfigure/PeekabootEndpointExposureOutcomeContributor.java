package org.peekaboot.autoconfigure;

import java.util.Set;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.EndpointExposureOutcomeContributor;
import org.springframework.boot.actuate.autoconfigure.endpoint.expose.EndpointExposure;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.core.env.Environment;

/**
 * Reports every web-capable actuator endpoint as exposed while peekaboot is
 * enabled, so {@code @ConditionalOnAvailableEndpoint} creates the endpoint
 * beans peekaboot invokes in-process — without requiring
 * {@code management.endpoints.web.exposure.include}. The regular HTTP mapping
 * under {@code /actuator} still applies that property, so no endpoint becomes
 * reachable over the web through this contributor.
 */
public class PeekabootEndpointExposureOutcomeContributor implements EndpointExposureOutcomeContributor {

    private static final String ENABLED_PROPERTY = PeekabootPropertyKeys.ENABLED;

    private final Environment environment;

    public PeekabootEndpointExposureOutcomeContributor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public ConditionOutcome getExposureOutcome(
            EndpointId endpointId, Set<EndpointExposure> exposures, ConditionMessage.Builder message) {
        if (!exposures.contains(EndpointExposure.WEB)) {
            return null;
        }
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
            return null;
        }
        return ConditionOutcome.match(message.because("peekaboot is enabled and invokes endpoints in-process"));
    }
}
