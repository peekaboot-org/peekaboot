package org.peekaboot.testingapp.integration;

import tools.jackson.databind.JsonNode;

/**
 * Small navigation helpers shared by the HTTP-level masking tests
 * ({@code ActuatorMaskingIT}, {@code UnmaskingDisabledIT},
 * {@code UnmaskingEnabledIT}) that need to find one specific property inside
 * the Config/Environment tabs' JSON shape.
 */
final class ActuatorInsightsJson {

    private ActuatorInsightsJson() {}

    /** ConfigInfo shape: {groups: [{prefix, properties: [{key, value}]}]}; the first group holding {@code key} wins. */
    static JsonNode findConfigInfoProperty(JsonNode configInfo, String key) {
        for (JsonNode group : configInfo.path("groups")) {
            JsonNode property = findProperty(group, key);
            if (property != null) {
                return property;
            }
        }
        return null;
    }

    /** For a key that is relative to its group, such as {@code password} under {@code spring.datasource}. */
    static JsonNode findConfigInfoProperty(JsonNode configInfo, String groupPrefix, String key) {
        for (JsonNode group : configInfo.path("groups")) {
            if (groupPrefix.equals(group.path("prefix").asString(null))) {
                return findProperty(group, key);
            }
        }
        return null;
    }

    private static JsonNode findProperty(JsonNode group, String key) {
        for (JsonNode property : group.path("properties")) {
            if (key.equals(property.path("key").asString(null))) {
                return property;
            }
        }
        return null;
    }

    /** EnvironmentInfo shape: {propertySources: [{properties: [{key, value}]}]}. */
    static JsonNode findEnvironmentPropertyValue(JsonNode environmentInfo, String key) {
        for (JsonNode source : environmentInfo.path("propertySources")) {
            for (JsonNode property : source.path("properties")) {
                if (key.equals(property.path("key").asString(null))) {
                    return property.path("value");
                }
            }
        }
        return null;
    }
}
