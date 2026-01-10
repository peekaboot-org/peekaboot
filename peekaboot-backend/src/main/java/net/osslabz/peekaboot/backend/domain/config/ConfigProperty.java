package net.osslabz.peekaboot.backend.domain.config;

public record ConfigProperty(
    String key,
    String value,
    String origin
) {}
