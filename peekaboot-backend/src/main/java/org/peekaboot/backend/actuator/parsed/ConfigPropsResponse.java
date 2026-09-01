package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

public record ConfigPropsResponse(Map<String, ConfigContext> contexts) {

    public record ConfigContext(Map<String, ConfigBean> beans, String parentId) {}

    public record ConfigBean(String prefix, Map<String, Object> properties) {}
}
