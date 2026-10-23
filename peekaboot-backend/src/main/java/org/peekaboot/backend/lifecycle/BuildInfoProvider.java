package org.peekaboot.backend.lifecycle;

import java.util.Map;
import org.springframework.boot.info.BuildProperties;

public class BuildInfoProvider {

    private final BuildProperties buildProperties;

    public BuildInfoProvider(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    public String getFormattedInfo() {
        if (buildProperties == null) {
            return "Build information not available";
        }
        return String.format(
                "%s:%s:%s", buildProperties.getGroup(), buildProperties.getArtifact(), buildProperties.getVersion());
    }

    /** The build-info entries the lifecycle log keeps; see {@link InfoEntries}. */
    public Map<String, String> getEntries() {
        return InfoEntries.of(buildProperties);
    }
}
