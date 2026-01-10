package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.application.ApplicationInfo;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
public class ApplicationMapper {

    @SuppressWarnings("unchecked")
    public ApplicationInfo map(Map<String, Object> info, Map<String, Object> spring) {
        Map<String, Object> build = Collections.emptyMap();
        Map<String, Object> git = Collections.emptyMap();
        String javaVersion = null;
        String javaVendor = null;

        if (info != null) {
            if (info.get("build") instanceof Map<?, ?> b) {
                build = (Map<String, Object>) b;
            }
            if (info.get("git") instanceof Map<?, ?> g) {
                git = (Map<String, Object>) g;
            }
            if (info.get("java") instanceof Map<?, ?> java) {
                javaVersion = getStringValue(java, "version");
                Object vendor = java.get("vendor");
                if (vendor instanceof Map<?, ?> v) {
                    javaVendor = getStringValue(v, "name");
                }
            }
        }

        String bootVersion = spring != null ? getStringValue(spring, "bootVersion") : null;
        String frameworkVersion = spring != null ? getStringValue(spring, "frameworkVersion") : null;

        return new ApplicationInfo(build, git, bootVersion, frameworkVersion, javaVersion, javaVendor);
    }

    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
