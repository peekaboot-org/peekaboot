package org.peekaboot.testingapp.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * Reads the span tree a trace of the insights API carries. The API serves the tree already
 * nested under {@code rootSpan}, so every read here walks {@code children} rather than
 * filtering a flat list by parent id.
 */
final class SpanTree {

    private SpanTree() {}

    /** Every span name in the trace, the root's included, in walk order. */
    static List<String> names(JsonNode trace) {
        List<String> names = new ArrayList<>();
        walk(trace.path("rootSpan"), span -> names.add(span.path("name").asString("")));
        return names;
    }

    /** Every tag key set on any span of the trace, so an assertion can name one that must be absent. */
    static List<String> tagKeys(JsonNode trace) {
        List<String> keys = new ArrayList<>();
        walk(trace.path("rootSpan"), span -> span.path("tags").propertyNames().forEach(keys::add));
        return keys;
    }

    /**
     * A span called {@code name} somewhere under the root, the root itself excluded. Fails
     * rather than answering empty: a caller asking for a descendant has already waited for it,
     * so its absence is the assertion failing and the names that were there are what explains
     * it.
     */
    static JsonNode descendantNamed(JsonNode trace, String name) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode child : trace.path("rootSpan").path("children")) {
            walk(child, span -> {
                if (name.equals(span.path("name").asString(""))) {
                    found.add(span);
                }
            });
        }
        if (found.isEmpty()) {
            throw new AssertionError("no span named '" + name + "' under the root span, among " + names(trace));
        }
        return found.getFirst();
    }

    private static void walk(JsonNode span, Consumer<JsonNode> visitor) {
        visitor.accept(span);
        for (JsonNode child : span.path("children")) {
            walk(child, visitor);
        }
    }
}
