package net.osslabz.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlywayResponse(
    Map<String, FlywayContext> contexts
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlywayContext(
        Map<String, FlywayBean> flywayBeans,
        String parentId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FlywayBean(
        List<Migration> migrations
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Migration(
        Long checksum,
        String description,
        Integer executionTime,
        String installedBy,
        String installedOn,
        Integer installedRank,
        String script,
        String state,
        String type,
        String version
    ) {
    }
}
