package org.peekaboot.backend.domain.application;

import java.util.Map;

/**
 * Minimal domain record for application information.
 *
 * Uses Map<String, Object> for build and git because:
 * - Actuator structure is stable and rarely changes
 * - No transformation needed, just pass-through
 * - Adding explicit typing would add maintenance burden without benefit
 */
public record ApplicationInfo(
        Map<String, Object> build,
        Map<String, Object> git,
        String springBootVersion,
        String springFrameworkVersion,
        String javaVersion,
        String javaVendor) {}
