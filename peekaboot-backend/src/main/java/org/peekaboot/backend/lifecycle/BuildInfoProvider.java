package org.peekaboot.backend.lifecycle;

import java.util.Map;
import org.springframework.boot.info.BuildProperties;

public class BuildInfoProvider {

    private final BuildProperties buildProperties;

    public BuildInfoProvider(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    public String getName() {
        return buildProperties != null ? buildProperties.getName() : "unknown";
    }

    public String getGroup() {
        return buildProperties != null ? buildProperties.getGroup() : "unknown";
    }

    public String getArtifact() {
        return buildProperties != null ? buildProperties.getArtifact() : "unknown";
    }

    public String getVersion() {
        return buildProperties != null ? buildProperties.getVersion() : "unknown";
    }

    public String getFormattedInfo() {
        if (buildProperties == null) {
            return "Build information not available";
        }
        return String.format("%s:%s:%s", getGroup(), getArtifact(), getVersion());
    }

    public boolean isBuildInfoAvailable() {
        return buildProperties != null;
    }

    /** Every build-info entry, including the ones no view reads today. */
    public Map<String, String> getAll() {
        return InfoEntries.of(buildProperties);
    }
}
