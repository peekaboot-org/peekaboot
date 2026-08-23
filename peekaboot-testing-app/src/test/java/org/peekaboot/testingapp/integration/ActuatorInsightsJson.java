package org.peekaboot.testingapp.integration;

import tools.jackson.databind.JsonNode;

/**
 * Small navigation helpers shared by the HTTP-level masking tests
 * ({@code ActuatorMaskingIntegrationTest}, {@code UnmaskingDisabledIntegrationTest},
 * {@code UnmaskingEnabledIntegrationTest}) that need to find one specific property inside
 * the Config/Environment tabs' JSON shape, or inside the raw {@code /configprops} payload.
 */
final class ActuatorInsightsJson {

    private ActuatorInsightsJson() {
    }

    /** ConfigInfo shape: {groups: [{properties: [{key, value}]}]}. */
    static JsonNode findConfigInfoProperty(JsonNode configInfo, String key) {
        for (JsonNode group : configInfo.path("groups")) {
            for (JsonNode property : group.path("properties")) {
                if (key.equals(property.path("key").asString(null))) {
                    return property;
                }
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

    /** Raw /configprops shape: {contexts: {<ctx>: {beans: {<bean>: {properties: {<key>: <value>}}}}}}. */
    static JsonNode findRawConfigPropsProperty(JsonNode configprops, String key) {
        for (JsonNode context : configprops.path("contexts").values()) {
            for (JsonNode bean : context.path("beans").values()) {
                JsonNode properties = bean.path("properties");
                if (properties.has(key)) {
                    return properties.path(key);
                }
            }
        }
        return null;
    }
}
