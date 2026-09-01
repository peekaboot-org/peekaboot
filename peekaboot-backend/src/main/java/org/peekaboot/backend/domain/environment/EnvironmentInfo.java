package org.peekaboot.backend.domain.environment;

import java.util.List;

/**
 * Domain record for environment information.
 *
 * Pre-groups properties by source to move grouping logic
 * from frontend to backend.
 */
public record EnvironmentInfo(List<String> activeProfiles, List<PropertySourceGroup> propertySources) {}
