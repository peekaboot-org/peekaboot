package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigPropsResponse(Map<String, ConfigContext> contexts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigContext(Map<String, ConfigBean> beans, String parentId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfigBean(String prefix, Map<String, Object> properties, Map<String, Object> inputs) {}
}
