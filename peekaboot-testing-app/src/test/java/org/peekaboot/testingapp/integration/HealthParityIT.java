package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Peekaboot's own walk of the health contributors against the application's {@link HealthEndpoint}:
 * the same statuses over the same component tree, plus what only Peekaboot adds, a duration on
 * every component.
 */
class HealthParityIT extends TestingAppITBase {

    private static final String HEALTH = "/peekaboot/api/health";

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void theReportMatchesTheApplicationsOwnHealth() {
        JsonNode served = ApiContract.servedBy(api).fetch(HEALTH).json();

        assertThat(tree(served)).isEqualTo(tree(jsonMapper.valueToTree(healthEndpoint.health())));
    }

    @Test
    void everyComponentAtEveryLevelCarriesItsDuration() {
        JsonNode served = ApiContract.servedBy(api).fetch(HEALTH).json();

        List<JsonNode> components = new ArrayList<>();
        collect(served, components);

        assertThat(components).hasSizeGreaterThan(served.path("components").size());
        assertThat(components).allSatisfy(component -> {
            assertThat(component.path("durationMs").isNumber()).isTrue();
            assertThat(component.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);
        });
    }

    /** The statuses and the nesting. Details are left out: disk space changes between two reads. */
    private static Map<String, Object> tree(JsonNode node) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("status", node.path("status").asString());
        Map<String, Object> components = new LinkedHashMap<>();
        node.path("components").properties().forEach(entry -> components.put(entry.getKey(), tree(entry.getValue())));
        if (!components.isEmpty()) {
            tree.put("components", components);
        }
        return tree;
    }

    private static void collect(JsonNode node, List<JsonNode> into) {
        node.path("components").properties().forEach(entry -> {
            into.add(entry.getValue());
            collect(entry.getValue(), into);
        });
    }
}
