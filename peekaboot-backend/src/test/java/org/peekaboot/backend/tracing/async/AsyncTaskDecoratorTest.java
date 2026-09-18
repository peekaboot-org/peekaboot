package org.peekaboot.backend.tracing.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.springframework.scheduling.support.DelegatingErrorHandlingRunnable;
import org.springframework.util.ErrorHandler;

class AsyncTaskDecoratorTest {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private final List<Observation.Context> recorded = new ArrayList<>();
    private AsyncTaskDecorator decorator;

    @BeforeEach
    void setUp() {
        // a handler that supports every context, so observations are real rather than no-ops
        // and getCurrentObservation() has something to return
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                recorded.add(context);
            }
        });
        decorator = new AsyncTaskDecorator(registry);
    }

    @Test
    void observesATaskDispatchedWithAnObservationInScope() {
        AtomicInteger runs = new AtomicInteger();
        Runnable decorated = decorator.decorate(runs::incrementAndGet);

        runWithParentObservationInScope(decorated);

        assertThat(runs).hasValue(1);
        assertThat(asyncContexts()).hasSize(1);
        assertThat(lowCardinalityValue(asyncContexts().get(0), AsyncTaskMarker.TAG_KEY))
                .isEqualTo(AsyncTaskMarker.TAG_VALUE);
    }

    @Test
    void namesTheThreadTheTaskRanOn() {
        Runnable decorated = decorator.decorate(() -> {});

        runWithParentObservationInScope(decorated);

        assertThat(highCardinalityValue(asyncContexts().get(0), AsyncTaskMarker.THREAD_TAG_KEY))
                .isEqualTo(Thread.currentThread().getName());
    }

    /**
     * The feature exists to continue a propagated trace. With nothing propagated there is no
     * trace to continue, and this is also what keeps the decorator off plain scheduled
     * execution, where the pool thread carries no scope.
     */
    @Test
    void runsTheTaskWithoutObservingItWhenNoObservationIsInScope() {
        AtomicInteger runs = new AtomicInteger();
        Runnable decorated = decorator.decorate(runs::incrementAndGet);

        decorated.run();

        assertThat(runs).hasValue(1);
        assertThat(asyncContexts()).isEmpty();
    }

    /**
     * One TaskDecorator bean is consumed by both the executor and the scheduler builders, and
     * ThreadPoolTaskScheduler decorates the scheduled future rather than the user task. Spring
     * already observes scheduled execution as tasks.scheduled.execution, deeper in the stack.
     */
    @Test
    void returnsAScheduledFutureUndecorated() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        // schedule() is declared to return ScheduledFuture; the object it actually hands back
        // is the RunnableScheduledFuture the decorator has to recognise
        Runnable scheduledFuture = (Runnable) scheduler.schedule(() -> {}, 1, TimeUnit.HOURS);

        Runnable decorated = decorator.decorate(scheduledFuture);

        assertThat(decorated).isSameAs(scheduledFuture);
        scheduler.shutdownNow();
    }

    /** SimpleAsyncTaskScheduler's shape, which Boot picks when virtual threads are on. */
    @Test
    void returnsAnErrorHandlingRunnableUndecorated() {
        ErrorHandler noop = throwable -> {};
        Runnable wrapped = new DelegatingErrorHandlingRunnable(() -> {}, noop);

        Runnable decorated = decorator.decorate(wrapped);

        assertThat(decorated).isSameAs(wrapped);
    }

    @Test
    void recordsAThrownErrorOnTheObservationAndRethrowsIt() {
        RuntimeException boom = new RuntimeException("boom");
        Runnable decorated = decorator.decorate(() -> {
            throw boom;
        });

        assertThatThrownBy(() -> runWithParentObservationInScope(decorated)).isSameAs(boom);

        assertThat(asyncContexts()).hasSize(1);
        assertThat(asyncContexts().get(0).getError()).isSameAs(boom);
    }

    private void runWithParentObservationInScope(Runnable task) {
        Observation parent = Observation.createNotStarted("parent", registry).start();
        try (Observation.Scope scope = parent.openScope()) {
            task.run();
        } finally {
            parent.stop();
        }
    }

    private List<Observation.Context> asyncContexts() {
        return recorded.stream()
                .filter(context -> AsyncTaskMarker.OBSERVATION_NAME.equals(context.getName()))
                .toList();
    }

    private static String lowCardinalityValue(Observation.Context context, String key) {
        return valueOf(context.getLowCardinalityKeyValues(), key);
    }

    private static String highCardinalityValue(Observation.Context context, String key) {
        return valueOf(context.getHighCardinalityKeyValues(), key);
    }

    private static String valueOf(Iterable<KeyValue> keyValues, String key) {
        for (KeyValue keyValue : keyValues) {
            if (keyValue.getKey().equals(key)) {
                return keyValue.getValue();
            }
        }
        return null;
    }
}
