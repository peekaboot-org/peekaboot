package org.peekaboot.backend.domain.property;

/** One property of a {@link PropertyGroup}, its value masked where it looks sensitive. */
public record Property(String key, String value) {}
