package org.peekaboot.backend.domain.environment;

import java.util.List;

public record PropertySourceGroup(String name, List<PropertyValue> properties) {}
