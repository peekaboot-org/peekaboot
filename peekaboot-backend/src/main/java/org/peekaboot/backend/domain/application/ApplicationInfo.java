package org.peekaboot.backend.domain.application;

import java.util.Map;

/** Application information; build and git stay untyped maps because the actuator structure is passed through as is. */
public record ApplicationInfo(
        Map<String, Object> build,
        Map<String, Object> git,
        String springBootVersion,
        String springFrameworkVersion,
        String javaVersion,
        String javaVendor) {}
