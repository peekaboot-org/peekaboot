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

    /** Every value {@code key} carries on any span of the trace, in walk order. */
    static List<String> tagValues(JsonNode trace, String key) {
        List<String> values = new ArrayList<>();
        walk(trace.path("rootSpan"), span -> {
            JsonNode value = span.path("tags").path(key);
            if (value.isString()) {
                values.add(value.asString());
            }
        });
        return values;
    }

    /** The value {@code key} carries on every span called {@code name}, in walk order. */
    static List<String> tagValuesOfSpansNamed(JsonNode trace, String name, String key) {
        List<String> values = new ArrayList<>();
        walk(trace.path("rootSpan"), span -> {
            if (name.equals(span.path("name").asString(""))) {
                values.add(span.path("tags").path(key).asString(""));
            }
        });
        return values;
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

    /** Every span called {@code name}, the root included, in walk order: an ancestor before its descendants. */
    static List<JsonNode> spansNamed(JsonNode trace, String name) {
        List<JsonNode> spans = new ArrayList<>();
        walk(trace.path("rootSpan"), span -> {
            if (name.equals(span.path("name").asString(""))) {
                spans.add(span);
            }
        });
        return spans;
    }

    static String spanId(JsonNode span) {
        return span.path("spanId").asString("");
    }

    /** The span names from the root to the first span called {@code name}, or an error when none is. */
    static List<String> pathTo(JsonNode trace, String name) {
        List<String> path = new ArrayList<>();
        if (!collectPath(trace.path("rootSpan"), name, path)) {
            throw new AssertionError("no span named '" + name + "' in the trace, among " + names(trace));
        }
        return path;
    }

    /** The statement text of every query at or below {@code span}, in walk order. */
    static List<String> statementsUnder(JsonNode span) {
        List<String> statements = new ArrayList<>();
        walk(span, node -> {
            String text = node.path("query").path("text").asString("");
            if (!text.isEmpty()) {
                statements.add(text);
            }
        });
        return statements;
    }

    /**
     * The first span, in walk order, whose statement contains {@code fragment}. Fails rather than
     * answering empty, naming every statement the trace held.
     */
    static JsonNode spanWithStatementContaining(JsonNode trace, String fragment) {
        List<JsonNode> found = new ArrayList<>();
        walk(trace.path("rootSpan"), span -> {
            if (span.path("query").path("text").asString("").contains(fragment)) {
                found.add(span);
            }
        });
        if (found.isEmpty()) {
            throw new AssertionError(
                    "no statement containing '" + fragment + "', among " + statementsUnder(trace.path("rootSpan")));
        }
        return found.getFirst();
    }

    private static boolean collectPath(JsonNode span, String name, List<String> path) {
        path.add(span.path("name").asString(""));
        if (name.equals(path.getLast())) {
            return true;
        }
        for (JsonNode child : span.path("children")) {
            if (collectPath(child, name, path)) {
                return true;
            }
        }
        path.removeLast();
        return false;
    }

    /** Every span of {@code kind} ({@code CLIENT}, {@code SERVER}, ...), the root included, in walk order. */
    static List<JsonNode> spansOfKind(JsonNode trace, String kind) {
        List<JsonNode> spans = new ArrayList<>();
        walk(trace.path("rootSpan"), span -> {
            if (kind.equals(span.path("kind").asString(""))) {
                spans.add(span);
            }
        });
        return spans;
    }

    private static void walk(JsonNode span, Consumer<JsonNode> visitor) {
        visitor.accept(span);
        for (JsonNode child : span.path("children")) {
            walk(child, visitor);
        }
    }
}
