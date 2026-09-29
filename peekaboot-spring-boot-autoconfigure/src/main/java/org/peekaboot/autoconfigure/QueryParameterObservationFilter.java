package org.peekaboot.autoconfigure;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.Comparator;
import java.util.List;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import net.ttddyy.observation.tracing.QueryContext;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.mapper.trace.DbSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a query's bind parameters on its span as {@link DbSpans#PARAMETERS_TAG}. The
 * OpenTelemetry query convention tags none, and datasource-micrometer's own
 * {@code jdbc.params[N]} is one comma-joined string that a value containing a comma breaks.
 *
 * <p>A filter rather than a handler: {@code Observation.stop()} runs every filter before any
 * handler's {@code onStop}, which is where the tracing handler copies the key values onto the
 * span and ends it.
 */
public final class QueryParameterObservationFilter implements ObservationFilter {

    /**
     * A batch past this many entries has its excess parameter sets dropped, not rendered; a
     * runaway batch should not turn one query into thousands of high-cardinality tag entries.
     */
    static final int MAX_PARAMETER_SETS = 100;

    private static final Logger log = LoggerFactory.getLogger(QueryParameterObservationFilter.class);

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof QueryContext query && query.getQueryInfoList() != null) {
            try {
                List<List<String>> parameterSets = parameterSets(query.getQueryInfoList());
                if (parameterSets.stream().anyMatch(set -> !set.isEmpty())) {
                    context.addHighCardinalityKeyValue(KeyValue.of(
                            DbSpans.PARAMETERS_TAG, PeekabootJson.MAPPER.writeValueAsString(parameterSets)));
                }
            } catch (RuntimeException e) {
                // called on every query - keep the log noise low; a bind value's own toString() can throw
                log.debug("Failed to render bind parameters as a SQL literal: {}", e.getMessage());
            }
        }
        return context;
    }

    private static List<List<String>> parameterSets(List<QueryInfo> queries) {
        return queries.stream()
                .flatMap(query -> query.getParametersList().stream())
                .limit(MAX_PARAMETER_SETS)
                .map(QueryParameterObservationFilter::literals)
                .toList();
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
