package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SnapshotRestoreBarrierTest {

    private static final InsightsSnapshot PERSISTED = new InsightsSnapshot(
            1_000,
            List.of(new InsightsSnapshot.Level(10_000, 90, 20_000, 1)),
            Map.of("cpu", List.<double[][]>of(new double[][] {{7.0}})));

    /**
     * The level threads arrive at their first boundaries independently, so the second one
     * can turn up while the first is still waiting for the source. The source parks the first
     * arrival until the second is seen blocked on the barrier, so the race is a certainty here.
     */
    @Test
    void twoLevelsArrivingTogetherAskTheSourceOnceAndRestoreOnce() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SnapshotRestoreBarrier barrier = new SnapshotRestoreBarrier(timeout -> {
            asked.incrementAndGet();
            sourceEntered.countDown();
            awaitQuietly(release);
            return Optional.of(PERSISTED);
        });
        List<InsightsSnapshot> restored = Collections.synchronizedList(new ArrayList<>());

        Thread first = Thread.ofPlatform().start(() -> barrier.arriveBefore(restored::add));
        sourceEntered.await();
        Thread second = Thread.ofPlatform().start(() -> barrier.arriveBefore(restored::add));
        await().atMost(Duration.ofSeconds(5)).until(() -> second.getState() == Thread.State.BLOCKED);
        release.countDown();
        first.join();
        second.join();

        assertThat(asked).hasValue(1);
        assertThat(restored).containsExactly(PERSISTED);
        assertThat(barrier.hasApplied()).isTrue();
    }

    @Test
    void aSourceWithNothingToRestoreIsAbandonedAfterOneAsk() {
        AtomicInteger asked = new AtomicInteger();
        SnapshotRestoreBarrier barrier = new SnapshotRestoreBarrier(timeout -> {
            asked.incrementAndGet();
            return Optional.empty();
        });
        List<InsightsSnapshot> restored = new ArrayList<>();

        barrier.arriveBefore(restored::add);
        barrier.arriveBefore(restored::add);

        assertThat(asked).hasValue(1);
        assertThat(restored).isEmpty();
        assertThat(barrier.hasApplied()).isFalse();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
