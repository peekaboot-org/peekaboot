package org.peekaboot.backend.lifecycle;

import java.util.List;
import org.springframework.core.env.Environment;

/** The profiles line of the ready banner. */
public class EnvironmentInfo {

    private final Environment environment;

    public EnvironmentInfo(Environment environment) {
        this.environment = environment;
    }

    /** The active profiles, or the default ones when none is active, since those are what Spring then runs. */
    public String getActiveProfilesAsString() {
        String[] active = environment.getActiveProfiles();
        List<String> profiles = List.of(active.length > 0 ? active : environment.getDefaultProfiles());
        return profiles.isEmpty() ? "none" : String.join(", ", profiles);
    }
}
