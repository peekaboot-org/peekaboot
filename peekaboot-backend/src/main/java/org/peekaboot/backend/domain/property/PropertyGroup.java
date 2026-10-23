package org.peekaboot.backend.domain.property;

import java.util.List;

/** A named group of properties: a {@code @ConfigurationProperties} prefix on the Config tab, a property source on the Environment tab. */
public record PropertyGroup(String name, List<Property> properties) {}
