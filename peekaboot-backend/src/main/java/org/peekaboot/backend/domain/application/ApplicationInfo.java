package org.peekaboot.backend.domain.application;

import java.util.Map;

/**
 * What the Overview's application cards show.
 *
 * @param build the consuming app's free-form {@code info.build} map, masked and otherwise passed through as is
 * @param git   the whitelisted git facts, null when the actuator reported none
 */
public record ApplicationInfo(
        Map<String, Object> build,
        GitInfo git,
        String springBootVersion,
        String springFrameworkVersion,
        String javaVersion,
        String javaVendor) {}
