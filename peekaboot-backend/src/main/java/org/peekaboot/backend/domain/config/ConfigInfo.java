package org.peekaboot.backend.domain.config;

import java.util.List;
import org.peekaboot.backend.domain.property.PropertyGroup;

/** Configuration properties, grouped by prefix with sensitive values masked. */
public record ConfigInfo(List<PropertyGroup> groups) {}
