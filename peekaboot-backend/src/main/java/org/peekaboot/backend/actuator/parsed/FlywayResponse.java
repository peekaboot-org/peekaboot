package org.peekaboot.backend.actuator.parsed;

import java.util.List;
import java.util.Map;

public record FlywayResponse(Map<String, FlywayContext> contexts) {

    public record FlywayContext(Map<String, FlywayBean> flywayBeans, String parentId) {}

    public record FlywayBean(List<Migration> migrations) {}

    public record Migration(
            String description,
            Integer executionTime,
            String installedOn,
            String script,
            String state,
            String type,
            String version) {}
}
