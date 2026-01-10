package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.health.HealthComponent;
import net.osslabz.peekaboot.backend.domain.health.HealthInfo;
import net.osslabz.peekaboot.backend.domain.health.HealthStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class HealthMapper {

    @SuppressWarnings("unchecked")
    public HealthInfo map(Map<String, Object> actuatorHealth) {
        if (actuatorHealth == null) {
            return new HealthInfo(HealthStatus.UNKNOWN, List.of());
        }

        // Handle body wrapper
        if (actuatorHealth.containsKey("body")) {
            actuatorHealth = (Map<String, Object>) actuatorHealth.get("body");
        }

        String statusStr = extractStatus(actuatorHealth.get("status"));
        HealthStatus status = HealthStatus.fromString(statusStr);

        List<HealthComponent> components = extractComponents(actuatorHealth);

        return new HealthInfo(status, components);
    }

    private String extractStatus(Object statusObj) {
        if (statusObj == null) return null;
        if (statusObj instanceof String s) return s;
        if (statusObj instanceof Map<?, ?> m) {
            Object code = m.get("code");
            if (code != null) return code.toString();
            Object name = m.get("name");
            if (name != null) return name.toString();
        }
        return statusObj.toString();
    }

    @SuppressWarnings("unchecked")
    private List<HealthComponent> extractComponents(Map<String, Object> health) {
        Object componentsObj = health.get("components");
        if (!(componentsObj instanceof Map<?, ?> componentsMap)) {
            return List.of();
        }

        List<HealthComponent> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : componentsMap.entrySet()) {
            String name = entry.getKey().toString();
            if (entry.getValue() instanceof Map<?, ?> componentData) {
                Map<String, Object> data = (Map<String, Object>) componentData;
                String statusStr = extractStatus(data.get("status"));
                HealthStatus componentStatus = HealthStatus.fromString(statusStr);

                Map<String, Object> details = Collections.emptyMap();
                if (data.get("details") instanceof Map<?, ?> d) {
                    details = (Map<String, Object>) d;
                }

                result.add(new HealthComponent(name, componentStatus, details));
            }
        }
        return result;
    }
}
