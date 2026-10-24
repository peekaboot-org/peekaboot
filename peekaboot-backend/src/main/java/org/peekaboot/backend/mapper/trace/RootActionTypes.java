package org.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import java.util.Map;
import java.util.Set;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;
import org.peekaboot.backend.tracing.tags.HttpSpanTags;
import org.peekaboot.backend.tracing.tags.Tags;

/**
 * What started a trace, read off its root span alone: the one classification behind a trace's
 * {@code rootActionType} and the listing's action-type filter, so the filter agrees with the row.
 */
public final class RootActionTypes {

    // What Lettuce's connection handshake (RedisHandshake) and Boot's Redis health indicators send.
    private static final Set<String> REDIS_UPKEEP_COMMANDS =
            Set.of("HELLO", "AUTH", "PING", "SELECT", "READONLY", "CLIENT", "INFO", "CLUSTER");

    private RootActionTypes() {}

    public static RootActionType of(SpanData rootSpan) {
        if (rootSpan == null) {
            return RootActionType.UNKNOWN;
        }

        Span.Kind kind = rootSpan.kind();
        Map<String, String> tags = rootSpan.tags() != null ? rootSpan.tags() : Map.of();

        // messaging.* tags say "a message was involved", not "we received one" - a
        // producer root span carries the same tags as a consumer. Only CONSUMER kind
        // states the direction outright; PRODUCER states the opposite, so it must not
        // fall into the tag arm below.
        if (kind == Span.Kind.CONSUMER || (kind != Span.Kind.PRODUCER && Tags.anyKeyStartsWith(tags, "messaging."))) {
            return RootActionType.MESSAGE_CONSUMER;
        }
        if (kind == Span.Kind.SERVER) {
            return detectServerActionType(tags);
        }
        return detectNonServerActionType(kind, rootSpan.name(), rootSpan.parentId(), tags);
    }

    /**
     * Classifies a root span that isn't inbound. Note what is deliberately absent: no
     * {@code http.}/{@code rpc.} check. Those tags on a non-SERVER root describe an
     * <em>outbound</em> call that became the root only because its caller's span hasn't
     * been exported yet, so they say nothing about what started the trace - UNKNOWN is
     * the honest answer there, not HTTP_REQUEST.
     */
    private static RootActionType detectNonServerActionType(
            Span.Kind kind, String name, String parentId, Map<String, String> tags) {
        // Peekaboot's own marker, so unambiguous - unlike the third-party tag families below,
        // which infer from someone else's convention. First for that reason, and because the
        // entry span carries no Span.Kind and would otherwise fall through to UNKNOWN.
        if (AsyncTaskMarker.isMarked(tags)) {
            return RootActionType.ASYNC_TASK;
        }
        // Spring's scheduled-task observation tag pair -> SCHEDULED_JOB. A genuine
        // @Scheduled invocation carries no Span.Kind (Micrometer only assigns one for
        // Sender/Receiver-style contexts), so this can't be pre-empted by the CLIENT-kind
        // branch below it, and without it the run would fall through to UNKNOWN.
        if (hasScheduledTaskTags(tags)) {
            return RootActionType.SCHEDULED_JOB;
        }
        // Before DATABASE, which every Redis command matches.
        if (isRedisUpkeepRoot(kind, parentId, tags)) {
            return RootActionType.MAINTENANCE;
        }
        // CLIENT-kind, not merely db.* tagged: only the client side of a query is a span
        // Peekaboot ever sees, so any other kind carrying db.* is not a database action.
        if (kind == Span.Kind.CLIENT && Tags.anyKeyStartsWith(tags, "db.")) {
            return RootActionType.DATABASE;
        }
        // datasource-micrometer's connection observation as the root: a pooled connection
        // acquired outside traced work (HikariCP maintenance). Checked after DATABASE because
        // every datasource observation in a HikariCP app carries jdbc.datasource.*; the fixed
        // contextual name "connection" (JdbcObservationDocumentation.CONNECTION) singles out
        // the acquisition.
        //
        // The parent check is what makes "outside traced work" true rather than merely
        // apparent, and it is the same caveat the http./rpc. note above states. A request on
        // an excluded prefix - Peekaboot's own, or the actuator's - has its root span skipped,
        // so a connection acquired while serving it is the only span stored under that trace
        // id and becomes the bundle's apparent root. Only a span that carried no parent at all
        // was really acquired outside a trace.
        if (kind == Span.Kind.CLIENT
                && parentId == null
                && "connection".equals(name)
                && Tags.anyKeyStartsWith(tags, "jdbc.datasource.")) {
            return RootActionType.MAINTENANCE;
        }
        return RootActionType.UNKNOWN;
    }

    /**
     * Classifies an inbound (SERVER-kind) root span. HTTP_REQUEST appears twice on
     * purpose: once as a positive {@code http.} match, and again as the fallback, because
     * an inbound span the app served with no protocol tags at all is far likelier to be an
     * HTTP request than anything else Peekaboot can name.
     */
    private static RootActionType detectServerActionType(Map<String, String> tags) {
        if (HttpSpanTags.describesHttpRequest(tags)) {
            return RootActionType.HTTP_REQUEST;
        }
        if (Tags.anyKeyStartsWith(tags, "rpc.")) {
            return RootActionType.RPC_CALL;
        }
        return RootActionType.HTTP_REQUEST;
    }

    /** Parentless for the reason the connection rule in {@link #detectNonServerActionType} gives. */
    private static boolean isRedisUpkeepRoot(Span.Kind kind, String parentId, Map<String, String> tags) {
        return kind == Span.Kind.CLIENT
                && parentId == null
                && "redis".equals(tags.get("db.system"))
                && REDIS_UPKEEP_COMMANDS.contains(tags.getOrDefault("db.operation", ""));
    }

    /**
     * Spring's DefaultScheduledTaskObservationConvention is the only convention that sets
     * this exact pair of low-cardinality keys (ScheduledTaskObservationDocumentation.
     * LowCardinalityKeyNames), so their presence together identifies a @Scheduled task
     * span reliably, unlike testing the span's name for "schedule"/"cron"/"timer"/"job".
     */
    private static boolean hasScheduledTaskTags(Map<String, String> tags) {
        return tags.containsKey("code.function") && tags.containsKey("code.namespace");
    }
}
