package org.peekaboot.backend.mapper.actuator;

import org.peekaboot.backend.actuator.raw.HealthResponse;
import org.peekaboot.backend.domain.health.HealthComponent;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class HealthMapper {

    private final TreeMasker treeMasker = new TreeMasker(new MaskingEngine());

    public HealthInfo map(HealthResponse health) {
        if (health == null || health.body() == null) {
            return new HealthInfo(HealthStatus.UNKNOWN, List.of());
        }

        HealthResponse.HealthBody body = health.body();
        HealthStatus status = HealthStatus.fromString(body.status());
        List<HealthComponent> components = extractComponents(body);

        return new HealthInfo(status, components);
    }

    private List<HealthComponent> extractComponents(HealthResponse.HealthBody body) {
        if (body.components() == null || body.components().isEmpty()) {
            return List.of();
        }

        List<HealthComponent> result = new ArrayList<>();
        for (Map.Entry<String, HealthResponse.HealthComponent> entry : body.components().entrySet()) {
            String name = entry.getKey();
            HealthResponse.HealthComponent component = entry.getValue();
            HealthStatus componentStatus = HealthStatus.fromString(component.status());
            Map<String, Object> details = component.details() != null ? component.details() : Collections.emptyMap();
            result.add(new HealthComponent(name, componentStatus, maskDetails(details)));
        }
        return result;
    }

    /**
     * A consuming app's custom HealthIndicator can put anything in details - unlike the
     * built-in indicators (db, diskSpace, ...), its shape isn't controlled here at all.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> maskDetails(Map<String, Object> details) {
        Object masked = treeMasker.mask(details);
        return masked instanceof Map ? (Map<String, Object>) masked : Collections.emptyMap();
    }
}
