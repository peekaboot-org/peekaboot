package org.peekaboot.backend.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.info.BuildProperties;
import org.springframework.util.StringUtils;

/**
 * What the application is called, read from its build-info and {@code spring.application.name}.
 * Each name takes what the build offers and falls back to the application name, which a build
 * without {@code build-info.properties} leaves as the only thing to go on.
 */
public final class ApplicationIdentity {

    @Nullable
    private final BuildProperties build;

    @Nullable
    private final String applicationName;

    public ApplicationIdentity(@Nullable BuildProperties build, @Nullable String applicationName) {
        this.build = build;
        this.applicationName = applicationName;
    }

    /**
     * {@code groupId.artifactId}, so two applications that share a {@code spring.application.name},
     * or have none at all, still keep their state apart.
     */
    public @Nullable String qualifiedId() {
        if (build != null && StringUtils.hasText(build.getGroup()) && StringUtils.hasText(build.getArtifact())) {
            return build.getGroup() + "." + build.getArtifact();
        }
        return applicationName;
    }

    /** The artifact alone: {@code com.acme.orders-admin} is a worse username than {@code orders-admin}. */
    public @Nullable String shortName() {
        if (build != null && StringUtils.hasText(build.getArtifact())) {
            return build.getArtifact();
        }
        return applicationName;
    }
}
