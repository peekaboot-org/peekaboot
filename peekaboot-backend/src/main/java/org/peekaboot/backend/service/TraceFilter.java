package org.peekaboot.backend.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceListParameters;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.mapper.trace.RootActionTypes;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.service.RowSources.RowSource;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.ListingRoots;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;

/**
 * What a listing row has to match. A null component, and an empty {@code actionTypes}, match every
 * row. The root, request and start filters read the row's listing facts; {@code outcome} and
 * {@code minDurationMs} read them too on a whole trace's row, where they equal its summary's, and
 * read an async row's own summary, which only mapping its subtree gives.
 *
 * <p>The facts are read before the row is mapped. A trace still changing in between can be listed
 * under a filter its served summary no longer matches: in progress when the filter read it and
 * complete when mapped, or {@code OK} then and {@code ERROR} now. A failure never clears, so
 * {@code outcome=ERROR} never lists a trace that reads {@code OK}.
 *
 * @param actionTypes the root action types listed; a request naming none gets every type but
 *     {@code MAINTENANCE}, and {@code *} every type, the empty set here
 * @param rootOperation part of the row's root span name, in any case
 * @param route the matched route pattern, exactly
 * @param pathPrefix the start of the request path, as written
 * @param statusCode the response status, exactly
 * @param outcome the row's {@code outcome}, an async row's own
 * @param minDurationMs the shortest {@code durationMs} listed, inclusive
 * @param window the row's {@code startTime}, half open
 */
record TraceFilter(
        Set<RootActionType> actionTypes,
        @Nullable String rootOperation,
        @Nullable String route,
        @Nullable String pathPrefix,
        @Nullable Integer statusCode,
        @Nullable TraceOutcome outcome,
        @Nullable Integer minDurationMs,
        TimeWindow window) {

    /**
     * The types a request that names none asks for. A maintenance trace is upkeep no traced
     * work caused, such as a HikariCP refill acquiring a connection or a Redis client's
     * handshake and health check. Enough of
     * them arrive to drown everything else, so they are kept in the store but out of the
     * default view.
     */
    private static final Set<RootActionType> DEFAULT_VIEW_TYPES =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(RootActionType.MAINTENANCE)));

    /** The one {@code rootActionType} value that asks for every type, the hidden ones included. */
    private static final String EVERY_TYPE = "*";

    /** How a refusal lists {@link #EVERY_TYPE}, which a list of types refuses as one of its tokens. */
    private static final String EVERY_TYPE_ALONE = EVERY_TYPE + " on its own";

    private static final int LOWEST_STATUS = 100;
    private static final int HIGHEST_STATUS = 599;

    static TraceFilter of(TraceListParameters parameters) {
        String outcome = RequestParameters.text(parameters.outcome());
        return new TraceFilter(
                actionTypesOf(parameters.rootActionType()),
                RequestParameters.text(parameters.rootOperation()),
                RequestParameters.text(parameters.route()),
                RequestParameters.text(parameters.path()),
                RequestParameters.integer("statusCode", parameters.statusCode(), LOWEST_STATUS, HIGHEST_STATUS),
                outcome == null ? null : RequestParameters.enumValue("outcome", TraceOutcome.class, outcome),
                RequestParameters.integer("minDurationMs", parameters.minDurationMs(), 0, Integer.MAX_VALUE),
                TimeWindow.of(parameters.since(), parameters.until()));
    }

    /**
     * Parses a comma-separated list of {@link RootActionType} names, refusing a name it does
     * not know. A request that names no type asks for {@link #DEFAULT_VIEW_TYPES};
     * {@link #EVERY_TYPE} asks for every type, which is the empty set here since an empty set
     * filters nothing away.
     */
    private static Set<RootActionType> actionTypesOf(@Nullable String rootActionType) {
        if (rootActionType == null || rootActionType.isBlank()) {
            return DEFAULT_VIEW_TYPES;
        }
        if (EVERY_TYPE.equals(rootActionType.trim())) {
            return Set.of();
        }
        Set<RootActionType> types = EnumSet.noneOf(RootActionType.class);
        for (String token : rootActionType.split(",", -1)) {
            types.add(RequestParameters.enumValue("rootActionType", RootActionType.class, token, EVERY_TYPE_ALONE));
        }
        return types;
    }

    /** Whether the request narrowed the listing at all. A request naming no type still gets the default view's filter. */
    boolean filtersAnything() {
        return !actionTypes.isEmpty()
                || !window.isOpen()
                || Stream.of(rootOperation, route, pathPrefix, statusCode, outcome, minDurationMs)
                        .anyMatch(Objects::nonNull);
    }

    /** {@code summaryOf} is asked only for an async row, and only while {@code outcome} or {@code minDurationMs} is set. */
    boolean admits(RowSource row, Function<RowSource, TraceSummary> summaryOf) {
        return matchesRoot(row.rootSpan())
                && matchesRequest(row.requestTags())
                && window.contains(row.startTime())
                && matchesVerdict(row, summaryOf);
    }

    /**
     * The type comes from {@link RootActionTypes}, as a mapped tree's does, so the verdict matches
     * what mapping the full tree would say. An empty type set and a null operation each filter
     * nothing away, and short-circuit before the classification a wildcard has no use for.
     */
    private boolean matchesRoot(@Nullable SpanData root) {
        return (actionTypes.isEmpty() || actionTypes.contains(RootActionTypes.of(root)))
                && (rootOperation == null || matchesRootOperation(root != null ? root.name() : null));
    }

    /** The chip sends a substring of the operation name, so a partial, case-insensitive match is the contract. */
    private boolean matchesRootOperation(@Nullable String rootOperationName) {
        if (rootOperationName == null) {
            return false;
        }
        String operation = rootOperationName.toLowerCase(Locale.ROOT);
        String filter = rootOperation.toLowerCase(Locale.ROOT);
        if (operation.contains(filter)) {
            return true;
        }
        // Scheduled task targets are fully qualified (package.Class.method) while span
        // names use the bean name ("task class.method") - also match on the Class.method suffix
        String[] segments = filter.split("\\.", -1);
        return segments.length > 2
                && operation.contains(segments[segments.length - 2] + "." + segments[segments.length - 1]);
    }

    /** {@code tags} is empty on a row that answered no request, which no request filter admits. */
    private boolean matchesRequest(Map<String, String> tags) {
        return matchesRoute(tags) && matchesPath(tags) && matchesStatus(tags);
    }

    private boolean matchesRoute(Map<String, String> tags) {
        return route == null || route.equals(HttpSpanTags.route(tags));
    }

    private boolean matchesPath(Map<String, String> tags) {
        return pathPrefix == null || startsWith(HttpSpanTags.path(tags), pathPrefix);
    }

    private static boolean startsWith(@Nullable String path, String prefix) {
        return path != null && path.startsWith(prefix);
    }

    private boolean matchesStatus(Map<String, String> tags) {
        return statusCode == null || statusCode.equals(HttpSpanTags.statusCode(tags));
    }

    private boolean matchesVerdict(RowSource row, Function<RowSource, TraceSummary> summaryOf) {
        if (!filtersVerdict()) {
            return true;
        }
        if (row.subtree()) {
            TraceSummary summary = summaryOf.apply(row);
            return matchesVerdict(summary.outcome(), summary.durationMs());
        }
        ListingRoots roots = row.roots();
        return matchesVerdict(TraceOutcome.of(roots.complete(), roots.failed()), roots.durationMs());
    }

    private boolean filtersVerdict() {
        return outcome != null || minDurationMs != null;
    }

    private boolean matchesVerdict(TraceOutcome rowOutcome, long durationMs) {
        return (outcome == null || outcome == rowOutcome) && (minDurationMs == null || durationMs >= minDurationMs);
    }
}
