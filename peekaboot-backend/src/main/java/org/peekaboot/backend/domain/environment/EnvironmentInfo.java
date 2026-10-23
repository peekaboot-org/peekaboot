package org.peekaboot.backend.domain.environment;

import java.util.List;
import org.peekaboot.backend.domain.property.PropertyGroup;

/** The active profiles and the properties, grouped by property source. */
public record EnvironmentInfo(List<String> activeProfiles, List<PropertyGroup> propertySources) {}
