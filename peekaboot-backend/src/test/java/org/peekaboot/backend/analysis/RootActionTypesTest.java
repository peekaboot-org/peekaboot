package org.peekaboot.backend.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.asyncTask;
import static org.peekaboot.backend.testsupport.Spans.jdbcConnection;
import static org.peekaboot.backend.testsupport.Spans.redisCommand;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.trace.RootActionType;

class RootActionTypesTest {

    /**
     * The full classification grid: every {@link Span.Kind}, plus the null kind Micrometer uses
     * for internal spans, against every tag family {@code RootActionTypes.of} inspects.
     * Scheduled-job detection keys on a tag <em>pair</em> rather than a prefix, so it sits
     * outside this grid and is covered by the named tests below.
     *
     * <p>Three rows look like misses and are not. CLIENT or null kind carrying {@code http.}/
     * {@code rpc.} tags is an outbound call that became the root only because its caller's span
     * hasn't arrived, so what started the trace is genuinely unknown. SERVER carrying {@code db.}
     * tags falls to the inbound default. PRODUCER carrying {@code messaging.} tags is a send, not
     * a receive.
     */
    @ParameterizedTest(name = "{0} kind with tags {1} -> {2}")
    @MethodSource("rootActionTypeGrid")
    void classifiesRootActionTypeFromKindAndTags(Span.Kind kind, Map<String, String> tags, RootActionType expected) {
        var rootSpan =
                span("root").named("root-op").kind(kind).at(0, 100).tags(tags).build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(expected);
    }

    @Test
    void detectsScheduledJobRootActionTypeFromScheduledTaskTags() {
        // The shape Spring's DefaultScheduledTaskObservationConvention actually produces:
        // no Span.Kind (it isn't a Sender/Receiver-style context) plus the code.function/
        // code.namespace low-cardinality tag pair.
        var rootSpan = span("root")
                .named("task orderReconciler.reconcileOrders")
                .at(0, 100)
                .tags(Map.of(
                        "code.function", "reconcileOrders",
                        "code.namespace", "org.peekaboot.testingapp.order.OrderReconciler"))
                .build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.SCHEDULED_JOB);
    }

    @Test
    void doesNotDetectScheduledJobFromNameAloneWithoutTags() {
        // A bean that merely happens to have "job" in its name must not be misclassified;
        // detection is tag-only. No scheduled-task tags -> falls through to the SERVER
        // default (HTTP_REQUEST), same as any other untagged SERVER-kind root span.
        var rootSpan = span("root")
                .named("batch-job-processor")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    @Test
    void doesNotDetectScheduledJobFromPartialTagPair() {
        // Both code.function and code.namespace must be present; code.function alone is not
        // enough (it's a generic low-cardinality key other conventions could also set).
        var rootSpan = span("root")
                .named("some-operation")
                .at(0, 100)
                .tags(Map.of("code.function", "reconcileOrders"))
                .build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.UNKNOWN);
    }

    @Test
    void doesNotDetectScheduledJobFromCodeTagsOnAServerKindRoot() {
        // Scheduled-task detection is deliberately confined to the kind-less path, because
        // that is the only shape Spring's convention produces. code.function/code.namespace
        // are ordinary OTel source-code attributes any instrumentation may set, so on a
        // SERVER-kind span they say nothing about scheduling and must not outrank the
        // server default.
        var rootSpan = span("root")
                .named("handle-request")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .tags(Map.of(
                        "code.function", "handleRequest",
                        "code.namespace", "org.peekaboot.testingapp.web.OrderController"))
                .build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.HTTP_REQUEST);
    }

    /** The target as Actuator's {@code scheduledtasks} endpoint names it, which the span's own name does not hold. */
    @Test
    void aScheduledJobNamesItsFullyQualifiedTarget() {
        var rootSpan = span("root")
                .named("task orderReconciler.reconcileOrders")
                .tags(Map.of(
                        "code.function", "reconcileOrders",
                        "code.namespace", "org.peekaboot.testingapp.order.OrderReconciler"))
                .build();

        assertThat(RootActionTypes.scheduledTarget(rootSpan))
                .isEqualTo("org.peekaboot.testingapp.order.OrderReconciler.reconcileOrders");
    }

    @ParameterizedTest
    @MethodSource("tagsShortOfTheTaskPair")
    void aRootWithoutBothTaskTagsNamesNoTarget(Map<String, String> tags) {
        var rootSpan = span("root")
                .named("task orderReconciler.reconcileOrders")
                .tags(tags)
                .build();

        assertThat(RootActionTypes.scheduledTarget(rootSpan)).isNull();
    }

    static Stream<Map<String, String>> tagsShortOfTheTaskPair() {
        return Stream.of(
                Map.of(),
                Map.of("code.function", "reconcileOrders"),
                Map.of("code.namespace", "org.peekaboot.testingapp.order.OrderReconciler"));
    }

    @Test
    void aMissingRootNamesNoTarget() {
        assertThat(RootActionTypes.scheduledTarget(null)).isNull();
    }

    @Test
    void detectsAsyncTaskRootActionTypeFromPeekabootsOwnMarker() {
        // The shape AsyncTaskDecorator produces: no Span.Kind, because a plain
        // Observation.Context is not a Sender/Receiver-style context, plus the marker key.
        var rootSpan = asyncTask("root").at(0, 100).build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.ASYNC_TASK);
    }

    /**
     * Peekaboot sets the marker itself, so it is the more specific signal wherever it appears.
     * The combination should be unreachable - guard 2 in AsyncTaskDecorator keeps the decorator
     * off scheduled tasks - and the integration test in Task 4 is what fails if it stops being.
     */
    @Test
    void theAsyncMarkerOutranksTheScheduledTaskTagPair() {
        var rootSpan = asyncTask("root")
                .at(0, 100)
                .tag("code.function", "enrich")
                .tag("code.namespace", "org.example.Enricher")
                .build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.ASYNC_TASK);
    }

    @Test
    void aSpanWithoutTheAsyncMarkerIsUnknown() {
        var rootSpan = span("root").named("some-operation").at(0, 100).build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.UNKNOWN);
    }

    /**
     * The exact shape datasource-micrometer exports when a pooled connection is acquired
     * outside any traced work (HikariCP maintenance): contextual name "connection", CLIENT
     * kind, no parent span, and only the {@code jdbc.datasource.*} connection keys as tags
     * - no {@code db.*}, no {@code jdbc.query[N]}.
     */
    @Test
    void classifiesAStandaloneConnectionSpanAsMaintenance() {
        var rootSpan = jdbcConnection("root").build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.MAINTENANCE);
    }

    /**
     * A connection acquired inside traced work, such as an excluded request whose root span
     * Peekaboot skips. Its parent id says what the tags cannot: it is not the standalone
     * acquisition MAINTENANCE names.
     */
    @Test
    void doesNotClassifyAConnectionSpanWithAnUnexportedParentAsMaintenance() {
        var connectionSpan =
                jdbcConnection("child").parent("root-span-never-exported").build();

        assertThat(RootActionTypes.of(connectionSpan)).isEqualTo(RootActionType.UNKNOWN);
    }

    @Test
    void keepsAQueryRootCarryingPoolTagsAsDatabase() {
        // In a HikariCP app every datasource observation gets jdbc.datasource.* added
        // (HikariJdbcObservationFilter tags query contexts too) - db.* still marks this
        // root as real database work, not pool maintenance.
        var rootSpan = span("root")
                .named("query")
                .kind(Span.Kind.CLIENT)
                .at(0, 30)
                .tags(Map.of(
                        "db.query.text", "SELECT 1",
                        "jdbc.datasource.name", "dataSource",
                        "jdbc.datasource.pool", "HikariPool-1"))
                .build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.DATABASE);
    }

    @Test
    void doesNotClassifyAConnectionNamedSpanWithoutDatasourceTagsAsMaintenance() {
        // The name only counts together with the jdbc.datasource.* tags the
        // datasource-micrometer convention sets - a bare span that happens to share the
        // name says nothing about what started the trace.
        var rootSpan = span("root")
                .named("connection")
                .kind(Span.Kind.CLIENT)
                .at(0, 30)
                .build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.UNKNOWN);
    }

    /**
     * Lettuce sends these while it sets a connection up (RedisHandshake) and Boot's Redis health
     * indicators send INFO or CLUSTER INFO. Reactive commands take their parent only from the
     * Reactor context, so each arrives parentless as a trace of its own.
     */
    @ParameterizedTest
    @ValueSource(strings = {"HELLO", "AUTH", "PING", "SELECT", "READONLY", "CLIENT", "INFO", "CLUSTER"})
    void classifiesAParentlessRedisUpkeepCommandAsMaintenance(String command) {
        var rootSpan = redisCommand("root", command).build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.MAINTENANCE);
    }

    /** The same fragment an excluded request leaves as the connection rule guards against. */
    @Test
    void keepsARedisUpkeepCommandWithAnUnexportedParentAsDatabase() {
        var command =
                redisCommand("child", "INFO").parent("root-span-never-exported").build();

        assertThat(RootActionTypes.of(command)).isEqualTo(RootActionType.DATABASE);
    }

    /** A parentless command the application issued stays listed, as a parentless JDBC query does. */
    @Test
    void keepsAParentlessRedisDataCommandAsDatabase() {
        var rootSpan = redisCommand("root", "SET").build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.DATABASE);
    }

    @Test
    void keepsAParentlessRedisSpanWithoutAnOperationAsDatabase() {
        var rootSpan =
                span("root").kind(Span.Kind.CLIENT).tag("db.system", "redis").build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.DATABASE);
    }

    /** The command names are Redis's; another database's operation of the same name is the application's work. */
    @Test
    void keepsAParentlessUpkeepNamedOperationOfAnotherDatabaseAsDatabase() {
        var rootSpan = redisCommand("root", "PING").tag("db.system", "mongodb").build();

        assertThat(RootActionTypes.of(rootSpan)).isEqualTo(RootActionType.DATABASE);
    }

    private static Stream<Arguments> rootActionTypeGrid() {
        Map<String, String> none = Map.of();
        Map<String, String> http = Map.of("http.method", "GET");
        Map<String, String> rpc = Map.of("rpc.system", "grpc");
        Map<String, String> messaging = Map.of("messaging.system", "kafka");
        Map<String, String> db = Map.of("db.system", "postgresql");
        return Stream.of(
                Arguments.of(Span.Kind.SERVER, none, RootActionType.HTTP_REQUEST),
                Arguments.of(Span.Kind.SERVER, http, RootActionType.HTTP_REQUEST),
                Arguments.of(Span.Kind.SERVER, rpc, RootActionType.RPC_CALL),
                Arguments.of(Span.Kind.SERVER, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.SERVER, db, RootActionType.HTTP_REQUEST),
                Arguments.of(Span.Kind.CLIENT, none, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CLIENT, http, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CLIENT, rpc, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CLIENT, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CLIENT, db, RootActionType.DATABASE),
                Arguments.of(Span.Kind.PRODUCER, none, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, http, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, rpc, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, messaging, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.PRODUCER, db, RootActionType.UNKNOWN),
                Arguments.of(Span.Kind.CONSUMER, none, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, http, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, rpc, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(Span.Kind.CONSUMER, db, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(null, none, RootActionType.UNKNOWN),
                Arguments.of(null, http, RootActionType.UNKNOWN),
                Arguments.of(null, rpc, RootActionType.UNKNOWN),
                Arguments.of(null, messaging, RootActionType.MESSAGE_CONSUMER),
                Arguments.of(null, db, RootActionType.UNKNOWN));
    }
}
