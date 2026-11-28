package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.actuator.parsed.HealthResponse.ComponentDescriptor;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;

class HealthWalkTest {

    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private static final HealthResponse UP_WITHOUT_MEMBERS = new HealthResponse("UP", Map.of());

    private final AtomicLong ticker = new AtomicLong();

    private final Executor inline = Runnable::run;

    private final DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();

    @Test
    void mirrorsTheContributorTree() {
        register("ping", () -> Health.up().withDetail("pong", true).build());
        registry.registerContributor(
                "db",
                composite(
                        "primary",
                                () -> Health.up().withDetail("database", "H2").build(),
                        "inventory",
                                () -> Health.up()
                                        .withDetail("database", "PostgreSQL")
                                        .build()));

        HealthResponse response = read(TEN_SECONDS, inline, member -> true, StatusAggregator.getDefault());

        assertThat(response)
                .isEqualTo(new HealthResponse(
                        "UP",
                        Map.of(
                                "ping", leaf("UP", 0, Map.of("pong", true)),
                                "db",
                                        branch(
                                                "UP",
                                                0,
                                                Map.of(
                                                        "primary", leaf("UP", 0, Map.of("database", "H2")),
                                                        "inventory",
                                                                leaf("UP", 0, Map.of("database", "PostgreSQL")))))));
        assertThat(response.components().keySet()).containsExactly("ping", "db");
        assertThat(response.components().get("db").components().keySet()).containsExactly("primary", "inventory");
    }

    @Test
    void leavesOutAndNeverStartsAChecksTheGroupExcludes() {
        AtomicInteger inventoryCalls = new AtomicInteger();
        registry.registerContributor("db", composite("primary", up(), "inventory", () -> {
            inventoryCalls.incrementAndGet();
            return Health.up().build();
        }));

        HealthResponse response =
                read(TEN_SECONDS, inline, member -> !member.equals("db/inventory"), StatusAggregator.getDefault());

        assertThat(response.components().get("db").components()).containsOnlyKeys("primary");
        assertThat(inventoryCalls).hasValue(0);
    }

    @Test
    void dropsACompositeLeftEmpty() {
        register("ping", up());
        registry.registerContributor("db", composite("primary", up(), "inventory", up()));

        HealthResponse response =
                read(TEN_SECONDS, inline, member -> !member.startsWith("db/"), StatusAggregator.getDefault());

        assertThat(response.components()).containsOnlyKeys("ping");
    }

    @Test
    void leavesOutACheckThatAnswersNull() {
        register("ping", up());
        register("silent", () -> null);

        HealthResponse response = read(TEN_SECONDS, inline, member -> true, StatusAggregator.getDefault());

        assertThat(response.components()).containsOnlyKeys("ping");
    }

    @Test
    void aCompositeWhoseChecksAllAnswerNullIsLeftOut() {
        register("ping", up());
        registry.registerContributor("db", composite("primary", () -> null, "inventory", () -> null));

        HealthResponse response = read(TEN_SECONDS, inline, member -> true, StatusAggregator.getDefault());

        assertThat(response.components()).containsOnlyKeys("ping");
    }

    @Test
    void aggregatesWithTheGroupsStatusAggregator() {
        register("ping", () -> Health.down().build());
        registry.registerContributor(
                "db", composite("primary", () -> Health.down().build(), "inventory", up()));
        StatusAggregator upRanksFirst = StatusAggregator.of("UP", "DOWN");

        HealthResponse response = read(TEN_SECONDS, inline, member -> true, upRanksFirst);

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.components().get("db").status()).isEqualTo("UP");
        assertThat(response.components().get("ping").status()).isEqualTo("DOWN");
    }

    @Test
    void aNullAggregateReadsUnknown() {
        registry.registerContributor("db", composite("primary", up(), "inventory", up()));
        StatusAggregator none = statuses -> null;

        HealthResponse response = read(TEN_SECONDS, inline, member -> true, none);

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.components().get("db").status()).isEqualTo("UNKNOWN");
        assertThat(response.components().get("db").details()).isEmpty();
    }

    @Test
    void aggregatorsSeeTheStatusesOfTheChildren() {
        register("ping", up());
        register("queue", () -> Health.status("BUSY").build());
        List<Set<Status>> seen = new ArrayList<>();
        StatusAggregator recording = statuses -> {
            seen.add(statuses);
            return Status.UP;
        };

        read(TEN_SECONDS, inline, member -> true, recording);

        assertThat(seen).containsExactly(Set.of(Status.UP, new Status("BUSY")));
    }

    @Test
    void noMemberReadsUp() {
        assertThat(read(TEN_SECONDS, inline, member -> true, StatusAggregator.getDefault()))
                .isEqualTo(UP_WITHOUT_MEMBERS);

        register("ping", up());
        registry.registerContributor("db", composite("primary", up(), "inventory", up()));

        assertThat(read(TEN_SECONDS, inline, member -> false, StatusAggregator.getDefault()))
                .isEqualTo(UP_WITHOUT_MEMBERS);
    }

    @Test
    void aCheckRunningAtTheDeadlineIsDownWhileTheOthersAnswer() {
        register("ping", up());
        registry.registerContributor("db", composite("primary", up(), "inventory", up()));
        List<Runnable> held = new ArrayList<>();
        AtomicInteger started = new AtomicInteger();
        int inventoryIsThird = 3;
        Executor holdingInventory = check -> {
            if (started.incrementAndGet() == inventoryIsThird) {
                held.add(check);
            } else {
                check.run();
            }
        };

        try (LogCapture log = LogCapture.attach(HealthCheckRuns.class)) {
            HealthResponse response =
                    read(Duration.ZERO, holdingInventory, member -> true, StatusAggregator.getDefault());
            held.forEach(Runnable::run);

            assertThat(response.status()).isEqualTo("DOWN");
            assertThat(response.components().get("ping").status()).isEqualTo("UP");
            assertThat(response.components().get("db").status()).isEqualTo("DOWN");
            assertThat(response.components()
                            .get("db")
                            .components()
                            .get("primary")
                            .status())
                    .isEqualTo("UP");
            ComponentDescriptor inventory =
                    response.components().get("db").components().get("inventory");
            assertThat(inventory.status()).isEqualTo("DOWN");
            assertThat(inventory.details()).isEqualTo(Map.of("error", "Timed out after 0ms"));
            assertThat(log.appender().list)
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            tuple(
                                    Level.WARN,
                                    "Peekaboot health: 'db/inventory' did not answer within 0ms; reported DOWN"),
                            tuple(Level.WARN, "Peekaboot health: 'db/inventory' answered UP after 0ms"));
        }
    }

    @Test
    void everyCheckStartsBeforeTheReadWaitsForAny() {
        CountDownLatch secondStarted = new CountDownLatch(1);
        register("first", () -> upIfStartedInTime(secondStarted));
        register("second", () -> {
            secondStarted.countDown();
            return Health.up().build();
        });

        HealthResponse response;
        try {
            response = new HealthWalk(HealthCheckRuns.onVirtualThreads(TEN_SECONDS, null))
                    .read(registry, new Group(member -> true, StatusAggregator.getDefault()));
        } finally {
            secondStarted.countDown();
        }

        assertThat(response.components()).containsOnlyKeys("first", "second");
        assertThat(response.components().values())
                .allSatisfy(check -> assertThat(check.status()).isEqualTo("UP"));
    }

    // a read that took a fresh deadline per check would wait out the whole timeout in real time
    @Test
    @Timeout(5)
    void allChecksOfOneReadShareOneDeadline() {
        List<Runnable> held = new ArrayList<>();
        register("first", up());
        register("second", up());
        AtomicBoolean deadlineTaken = new AtomicBoolean();
        // the deadline is the first reading once both checks are held; every later one is past it
        LongSupplier clock = () -> {
            if (held.size() == 2 && deadlineTaken.compareAndSet(false, true)) {
                return 0;
            }
            return deadlineTaken.get() ? TEN_SECONDS.toNanos() + 1 : 0;
        };

        try (LogCapture log = LogCapture.attach(HealthCheckRuns.class)) {
            HealthResponse response = new HealthWalk(new HealthCheckRuns(TEN_SECONDS, null, held::add, clock))
                    .read(registry, new Group(member -> true, StatusAggregator.getDefault()));
            held.forEach(Runnable::run);

            assertThat(response.components().values()).hasSize(2).allSatisfy(check -> {
                assertThat(check.status()).isEqualTo("DOWN");
                assertThat(check.details()).isEqualTo(Map.of("error", "Timed out after 10s"));
                assertThat(check.durationMs()).isEqualTo(TEN_SECONDS.toMillis());
            });
            assertThat(log.appender().list)
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            tuple(Level.WARN, "Peekaboot health: 'first' did not answer within 10s; reported DOWN"),
                            tuple(Level.WARN, "Peekaboot health: 'second' did not answer within 10s; reported DOWN"),
                            tuple(Level.WARN, "Peekaboot health: 'first' answered UP after 10000ms"),
                            tuple(Level.WARN, "Peekaboot health: 'second' answered UP after 10000ms"));
        }
    }

    @Test
    void aCompositeTakesItsSlowestChildsDuration() {
        registry.registerContributor(
                "db",
                composite(
                        "primary", after(30, Health.up().build()),
                        "inventory", after(70, Health.up().build())));

        HealthResponse response = read(TEN_SECONDS, inline, member -> true, StatusAggregator.getDefault());

        ComponentDescriptor db = response.components().get("db");
        assertThat(db.components().get("primary").durationMs()).isEqualTo(30);
        assertThat(db.components().get("inventory").durationMs()).isEqualTo(70);
        assertThat(db.durationMs()).isEqualTo(70);
    }

    @Test
    void membershipAndDurationsHoldTwoLevelsDeep() {
        AtomicInteger otherCalls = new AtomicInteger();
        registry.registerContributor(
                "outer",
                CompositeHealthContributor.fromMap(Map.of(
                        "inner", composite("leaf", after(20, Health.up().build()), "other", () -> {
                            otherCalls.incrementAndGet();
                            return Health.up().build();
                        }))));

        HealthResponse response =
                read(TEN_SECONDS, inline, member -> !member.equals("outer/inner/other"), StatusAggregator.getDefault());

        ComponentDescriptor outer = response.components().get("outer");
        assertThat(outer.components().get("inner").components()).containsOnlyKeys("leaf");
        assertThat(outer.durationMs()).isEqualTo(20);
        assertThat(otherCalls).hasValue(0);
    }

    private HealthResponse read(
            Duration timeout, Executor executor, Predicate<String> members, StatusAggregator aggregator) {
        HealthCheckRuns runs = new HealthCheckRuns(timeout, null, executor, ticker::get);
        return new HealthWalk(runs).read(registry, new Group(members, aggregator));
    }

    private void register(String name, HealthIndicator indicator) {
        registry.registerContributor(name, indicator);
    }

    /** UP once the latch is counted down, DOWN if that takes longer than five seconds. */
    private static Health upIfStartedInTime(CountDownLatch started) {
        try {
            return started.await(5, TimeUnit.SECONDS)
                    ? Health.up().build()
                    : Health.down().build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        }
    }

    private static HealthIndicator up() {
        return () -> Health.up().build();
    }

    private HealthIndicator after(long millis, Health answer) {
        return () -> {
            ticker.addAndGet(Duration.ofMillis(millis).toNanos());
            return answer;
        };
    }

    private static CompositeHealthContributor composite(
            String firstName, HealthIndicator first, String secondName, HealthIndicator second) {
        Map<String, HealthIndicator> children = new LinkedHashMap<>();
        children.put(firstName, first);
        children.put(secondName, second);
        return CompositeHealthContributor.fromMap(children);
    }

    private static ComponentDescriptor leaf(String status, long durationMs, Map<String, Object> details) {
        return new ComponentDescriptor(status, durationMs, details, Map.of());
    }

    private static ComponentDescriptor branch(
            String status, long durationMs, Map<String, ComponentDescriptor> components) {
        return new ComponentDescriptor(status, durationMs, Map.of(), components);
    }

    private record Group(Predicate<String> members, StatusAggregator aggregator) implements HealthEndpointGroup {

        @Override
        public boolean isMember(String name) {
            return members.test(name);
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            return true;
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return true;
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return aggregator;
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return HttpCodeStatusMapper.getDefault();
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
            return null;
        }
    }
}
