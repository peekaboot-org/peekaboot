package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.health.HealthComponent;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;
import org.springframework.stereotype.Component;

@Component
public class HealthMapper {

    private final TreeMasker treeMasker = new TreeMasker(new MaskingEngine());

    public HealthInfo map(HealthResponse health, boolean unmask) {
        if (health == null) {
            return new HealthInfo(HealthStatus.UNKNOWN, List.of());
        }

        HealthStatus status = HealthStatus.fromString(health.status());
        List<HealthComponent> components = extractComponents(health, unmask);

        return new HealthInfo(status, components);
    }

    private List<HealthComponent> extractComponents(HealthResponse health, boolean unmask) {
        List<HealthComponent> result = new ArrayList<>();
        appendComponents("", health.components(), unmask, result);
        return result;
    }

    /**
     * The dashboard shows one flat list, so a composite's children follow it named
     * {@code parent/child}; the composite itself keeps its aggregate status and, having no
     * details of its own, an empty details map.
     */
    private void appendComponents(
            String namePrefix,
            Map<String, HealthResponse.HealthComponent> components,
            boolean unmask,
            List<HealthComponent> result) {
        if (components == null) {
            return;
        }
        for (Map.Entry<String, HealthResponse.HealthComponent> entry : components.entrySet()) {
            String name = namePrefix + entry.getKey();
            HealthResponse.HealthComponent component = entry.getValue();
            HealthStatus componentStatus = HealthStatus.fromString(component.status());
            Map<String, Object> details = component.details() != null ? component.details() : Collections.emptyMap();
            result.add(new HealthComponent(name, componentStatus, maskDetails(details, unmask)));
            appendComponents(name + "/", component.components(), unmask, result);
        }
    }

    /**
     * A consuming app's custom HealthIndicator can put anything in details - unlike the
     * built-in indicators (db, diskSpace, ...), its shape isn't controlled here at all.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> maskDetails(Map<String, Object> details, boolean unmask) {
        Object masked = treeMasker.mask(details, unmask);
        return masked instanceof Map ? (Map<String, Object>) masked : Collections.emptyMap();
    }
}
