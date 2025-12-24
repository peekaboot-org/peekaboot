package net.osslabz.peekaboot.backend.lifecycle;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

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

    public boolean isDefaultProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles == null || activeProfiles.length == 0;
    }

    public String getActiveProfilesAsString() {
        List<String> profiles = getActiveProfiles();
        return profiles.isEmpty() ? "none" : String.join(", ", profiles);
    }
}
