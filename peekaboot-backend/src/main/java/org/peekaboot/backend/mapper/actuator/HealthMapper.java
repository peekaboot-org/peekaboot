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

    public HealthInfo map(HealthResponse health) {
        return map(health, false);
    }

    /**
     * Same as {@link #map(HealthResponse)}, except when {@code unmask} is true, in which
     * case every component detail value is returned verbatim. See
     * {@link MaskingEngine#mask(String, String, boolean)} for why this shape.
     */
    public HealthInfo map(HealthResponse health, boolean unmask) {
        if (health == null || health.body() == null) {
            return new HealthInfo(HealthStatus.UNKNOWN, List.of());
        }

        HealthResponse.HealthBody body = health.body();
        HealthStatus status = HealthStatus.fromString(body.status());
        List<HealthComponent> components = extractComponents(body, unmask);

        return new HealthInfo(status, components);
    }

    private List<HealthComponent> extractComponents(HealthResponse.HealthBody body, boolean unmask) {
        if (body.components() == null || body.components().isEmpty()) {
            return List.of();
        }

        List<HealthComponent> result = new ArrayList<>();
        for (Map.Entry<String, HealthResponse.HealthComponent> entry :
                body.components().entrySet()) {
            String name = entry.getKey();
            HealthResponse.HealthComponent component = entry.getValue();
            HealthStatus componentStatus = HealthStatus.fromString(component.status());
            Map<String, Object> details = component.details() != null ? component.details() : Collections.emptyMap();
            result.add(new HealthComponent(name, componentStatus, maskDetails(details, unmask)));
        }
        return result;
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
