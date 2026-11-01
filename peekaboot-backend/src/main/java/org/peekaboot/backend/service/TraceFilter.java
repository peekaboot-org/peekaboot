package org.peekaboot.backend.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceListParameters;
import org.peekaboot.backend.mapper.trace.RootActionTypes;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.service.RowSources.RowSource;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * What a listing row has to match, read off its own root span: no tree is copied, built or
 * masked for a row the response will not carry. An empty {@code actionTypes} and a null
 * {@code rootOperation} match every row.
 */
record TraceFilter(
        Set<RootActionType> actionTypes, @Nullable String rootOperation) {

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

    static TraceFilter of(TraceListParameters parameters) {
        return new TraceFilter(
                actionTypesOf(parameters.rootActionType()), RequestParameters.text(parameters.rootOperation()));
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
        return !actionTypes.isEmpty() || rootOperation != null;
    }

    boolean admits(RowSource row) {
        return matchesRoot(row.rootSpan());
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
}
