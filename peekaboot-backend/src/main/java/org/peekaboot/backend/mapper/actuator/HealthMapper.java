package org.peekaboot.backend.mapper.actuator;

import org.peekaboot.backend.actuator.raw.HealthResponse;
import org.peekaboot.backend.domain.health.HealthComponent;
import org.peekaboot.backend.domain.health.HealthInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class HealthMapper {

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
            result.add(new HealthComponent(name, componentStatus, details));
        }
        return result;
    }
}
