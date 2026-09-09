package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.health.HealthComponent;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;

public class HealthMapper {

    private final TreeMasker treeMasker;

    public HealthMapper(MaskingEngine maskingEngine) {
        this.treeMasker = new TreeMasker(maskingEngine);
    }

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
        for (Map.Entry<String, HealthResponse.HealthComponent> entry : components.entrySet()) {
            String name = namePrefix + entry.getKey();
            HealthResponse.HealthComponent component = entry.getValue();
            HealthStatus componentStatus = HealthStatus.fromString(component.status());
            // a custom HealthIndicator can put anything in details, so they are masked as a tree
            result.add(new HealthComponent(name, componentStatus, treeMasker.maskMap(component.details(), unmask)));
            appendComponents(name + "/", component.components(), unmask, result);
        }
    }
}
