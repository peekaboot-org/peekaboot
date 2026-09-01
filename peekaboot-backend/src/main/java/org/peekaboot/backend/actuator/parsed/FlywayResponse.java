package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlywayResponse(Map<String, FlywayContext> contexts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlywayContext(Map<String, FlywayBean> flywayBeans, String parentId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlywayBean(List<Migration> migrations) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Migration(
            String description,
            Integer executionTime,
            String installedOn,
            String script,
            String state,
            String type,
            String version) {}
}
