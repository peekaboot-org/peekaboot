package org.peekaboot.backend.domain.application;

import java.util.Map;

/**
 * What the Overview's application cards show.
 *
 * @param build the consuming app's free-form {@code info.build} map, masked and otherwise passed through as is
 * @param git   the whitelisted facts of the application's own git.properties, null where it has none or one without branch and commit, where the host turns Boot's git info contributor off, and before the application has started
 */
public record ApplicationInfo(
        Map<String, Object> build,
        GitInfo git,
        String springBootVersion,
        String springFrameworkVersion,
        String javaVersion,
        String javaVendor) {}
