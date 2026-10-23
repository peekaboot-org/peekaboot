package org.peekaboot.testingapp.integration;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Reads a trace's {@code queries} list, the Queries tab's own data, by DataSource or by statement.
 * A query's statement is its span's {@code query}, as the Queries tab reads it.
 */
final class TraceQueries {

    private TraceQueries() {}

    /** The DataSource name of every query, in list order; empty where the instrumentation named none. */
    static List<String> dataSourceNames(JsonNode trace) {
        List<String> names = new ArrayList<>();
        trace.path("queries")
                .forEach(query -> names.add(query.path("dataSourceName").asString("")));
        return names;
    }

    /** The statement text, as served, of every query that ran on {@code dataSourceName}. */
    static List<String> statementsOn(JsonNode trace, String dataSourceName) {
        List<String> statements = new ArrayList<>();
        trace.path("queries").forEach(query -> {
            if (dataSourceName.equals(query.path("dataSourceName").asString(""))) {
                statements.add(statementOf(trace, query).path("text").asString(""));
            }
        });
        return statements;
    }

    /**
     * The one query whose statement contains {@code fragment}. Fails unless exactly one does,
     * naming every statement the trace held.
     */
    static JsonNode queryContaining(JsonNode trace, String fragment) {
        List<JsonNode> found = new ArrayList<>();
        List<String> statements = new ArrayList<>();
        trace.path("queries").forEach(query -> {
            String statement = statementOf(trace, query).path("text").asString("");
            statements.add(statement);
            if (statement.contains(fragment)) {
                found.add(query);
            }
        });
        if (found.size() != 1) {
            throw new AssertionError(found.size() + " statements containing '" + fragment + "', among " + statements);
        }
        return found.getFirst();
    }

    /** The statement of {@code query}, one entry of {@code trace}'s list: its span's {@code statement}. */
    static JsonNode statementOf(JsonNode trace, JsonNode query) {
        return SpanTree.spanWithId(trace, SpanTree.spanId(query)).path("statement");
    }
}
