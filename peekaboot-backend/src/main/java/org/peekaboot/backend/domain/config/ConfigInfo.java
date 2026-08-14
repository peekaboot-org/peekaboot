package org.peekaboot.backend.domain.config;

import java.util.List;

/**
 * Domain record for configuration properties.
 *
 * Pre-groups properties by prefix and masks sensitive values
 * to move this logic from frontend to backend.
 */
public record ConfigInfo(
    List<ConfigGroup> groups
) {}
