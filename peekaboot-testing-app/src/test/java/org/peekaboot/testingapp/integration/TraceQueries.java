package org.peekaboot.testingapp.integration;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Reads a trace's {@code queries} list, the Queries tab's own data, by the DataSource each query ran on. */
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
                statements.add(query.path("statement").path("text").asString(""));
            }
        });
        return statements;
    }
}
