package net.osslabz.peekaboot.backend.domain.environment;

public record PropertyValue(
    String key,
    String value,
    String origin
) {}
