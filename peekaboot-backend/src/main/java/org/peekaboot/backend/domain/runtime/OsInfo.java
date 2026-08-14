package org.peekaboot.backend.domain.runtime;

public record OsInfo(
    String name,
    String version,
    String arch
) {}
