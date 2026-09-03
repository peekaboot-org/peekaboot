package org.peekaboot.backend.lifecycle;

import java.util.Arrays;
import java.util.List;
import org.springframework.core.env.Environment;

public class EnvironmentInfo {

    private final Environment environment;

    public EnvironmentInfo(Environment environment) {
        this.environment = environment;
    }

    public List<String> getActiveProfiles() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles != null && activeProfiles.length > 0) {
            return Arrays.asList(activeProfiles);
        }
        return getDefaultProfiles();
    }

    public List<String> getDefaultProfiles() {
        String[] defaultProfiles = environment.getDefaultProfiles();
        return defaultProfiles != null ? Arrays.asList(defaultProfiles) : List.of();
    }

    public String getActiveProfilesAsString() {
        List<String> profiles = getActiveProfiles();
        return profiles.isEmpty() ? "none" : String.join(", ", profiles);
    }
}
