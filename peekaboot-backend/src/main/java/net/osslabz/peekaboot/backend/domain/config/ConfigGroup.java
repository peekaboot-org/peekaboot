package net.osslabz.peekaboot.backend.domain.config;

import java.util.List;

public record ConfigGroup(
    String prefix,
    List<ConfigProperty> properties
) {}
