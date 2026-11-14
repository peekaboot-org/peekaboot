package org.peekaboot.backend.tracing.query;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import net.ttddyy.observation.tracing.QueryContext;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.tracing.tags.QueryParameterTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a query's bind parameters on its span as {@link QueryParameterTags#PARAMETERS_TAG}. The
 * OpenTelemetry query convention tags none, and datasource-micrometer's own
 * {@code jdbc.params[N]} is one comma-joined string that a value containing a comma breaks.
 *
 * <p>One statement records at most {@link #MAX_PARAMETER_SETS} parameter sets and
 * {@link #MAX_PARAMETER_CHARACTERS} characters of literals. A batch past either limit keeps its
 * leading sets and counts the rest as {@link QueryParameterTags#OMITTED_PARAMETER_SETS_TAG}. A
 * first set past the character limit keeps its leading literals and a comment counting the rest.
 *
 * <p>A filter rather than a handler: {@code Observation.stop()} runs every filter before any
 * handler's {@code onStop}, which is where the tracing handler copies the key values onto the
 * span and ends it.
 */
public final class QueryParameterObservationFilter implements ObservationFilter {

    /**
     * A batch past this many entries has its excess parameter sets dropped and only counted, as
     * {@link QueryParameterTags#OMITTED_PARAMETER_SETS_TAG}; a runaway batch should not turn one query into
     * thousands of high-cardinality tag entries.
     */
    static final int MAX_PARAMETER_SETS = 100;

    /**
     * The literals of one statement share this many characters. {@link SqlLiterals} bounds one
     * literal and {@link #MAX_PARAMETER_SETS} the sets of a batch, and neither bounds the
     * parameters of a set: an IN list of thousands of long strings would put megabytes on one span.
     */
    static final int MAX_PARAMETER_CHARACTERS = 16_000;

    private static final Logger log = LoggerFactory.getLogger(QueryParameterObservationFilter.class);

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof QueryContext query && query.getQueryInfoList() != null) {
            try {
                List<List<ParameterSetOperation>> batch = query.getQueryInfoList().stream()
                        .flatMap(info -> info.getParametersList().stream())
                        .toList();
                List<List<String>> parameterSets = withinTheLimits(batch);
                if (parameterSets.stream().anyMatch(set -> !set.isEmpty())) {
                    context.addHighCardinalityKeyValue(KeyValue.of(
                            QueryParameterTags.PARAMETERS_TAG, PeekabootJson.MAPPER.writeValueAsString(parameterSets)));
                    int omitted = batch.size() - parameterSets.size();
                    if (omitted > 0) {
                        context.addHighCardinalityKeyValue(
                                KeyValue.of(QueryParameterTags.OMITTED_PARAMETER_SETS_TAG, String.valueOf(omitted)));
                    }
                }
            } catch (RuntimeException e) {
                // called on every query - keep the log noise low; a bind value's own toString() can throw
                log.debug("Failed to render bind parameters as a SQL literal: {}", e.getMessage());
            }
        }
        return context;
    }

    /**
     * The leading sets that fit both limits. A first set past the character limit is cut:
     * dropped whole, it would leave the statement no parameter at all.
     */
    private static List<List<String>> withinTheLimits(List<List<ParameterSetOperation>> batch) {
        List<List<String>> kept = new ArrayList<>();
        long remaining = MAX_PARAMETER_CHARACTERS;
        for (List<ParameterSetOperation> operations : batch) {
            if (kept.size() == MAX_PARAMETER_SETS) {
                break;
            }
            List<String> literals = literals(operations);
            long length = literals.stream().mapToLong(String::length).sum();
            if (length > remaining) {
                if (kept.isEmpty()) {
                    kept.add(cut(literals));
                }
                break;
            }
            kept.add(literals);
            remaining -= length;
        }
        return kept;
    }

    /** The leading literals that fit the character limit, then a comment counting the parameters left out. */
    private static List<String> cut(List<String> literals) {
        List<String> kept = new ArrayList<>();
        long remaining = MAX_PARAMETER_CHARACTERS;
        for (String literal : literals) {
            if (literal.length() > remaining) {
                break;
            }
            kept.add(literal);
            remaining -= literal.length();
        }
        kept.add(SqlLiterals.omitted(literals.size() - kept.size(), "parameter"));
        return kept;
    }

    /** Index-bound values only: a name says nothing about which placeholder it fills. */
    private static List<String> literals(List<ParameterSetOperation> operations) {
        return operations.stream()
                .filter(operation -> operation.getArgs()[0] instanceof Integer)
                .filter(operation -> !ParameterSetOperation.isRegisterOutParameterOperation(operation))
                .sorted(Comparator.comparingInt(operation -> (Integer) operation.getArgs()[0]))
                .map(QueryParameterObservationFilter::literal)
                .toList();
    }

    private static String literal(ParameterSetOperation operation) {
        return ParameterSetOperation.isSetNullParameterOperation(operation)
                ? SqlLiterals.NULL
                : SqlLiterals.render(operation.getArgs()[1]);
    }
}
