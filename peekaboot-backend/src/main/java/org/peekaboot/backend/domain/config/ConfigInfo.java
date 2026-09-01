package org.peekaboot.backend.domain.config;

import java.util.List;

/** Configuration properties, grouped by prefix with sensitive values masked. */
public record ConfigInfo(List<ConfigGroup> groups) {}
