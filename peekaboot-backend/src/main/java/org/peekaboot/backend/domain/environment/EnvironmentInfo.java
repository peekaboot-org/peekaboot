package org.peekaboot.backend.domain.environment;

import java.util.List;

/** The active profiles and the properties, grouped by property source. */
public record EnvironmentInfo(List<String> activeProfiles, List<PropertySourceGroup> propertySources) {}
