package org.peekaboot.backend.tracing.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.support.DelegatingErrorHandlingRunnable;
import org.springframework.util.ErrorHandler;

class AsyncTaskDecoratorTest {

    private final ObservationRegistry registry = ObservationRegistry.create();
    private final List<Observation.Context> recorded = new CopyOnWriteArrayList<>();
    private final AtomicInteger errorsRecorded = new AtomicInteger();
    private final BlockingQueue<Observation.Context> stopped = new LinkedBlockingQueue<>();
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

            @Override
            public void onError(Observation.Context context) {
                errorsRecorded.incrementAndGet();
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
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

    /** Both of Spring's executors turn submit() into a FutureTask, which keeps the exception to itself. */
    @Test
    void recordsTheFailureOfASubmittedTaskAndLeavesItInTheFuture() {
        RuntimeException boom = new RuntimeException("boom");
        FutureTask<Object> submitted = new FutureTask<>(() -> {
            throw boom;
        });

        runWithParentObservationInScope(decorator.decorate(submitted));

        assertThat(asyncContexts())
                .singleElement()
                .satisfies(task -> assertThat(task.getError()).isSameAs(boom));
        assertThatThrownBy(submitted::get)
                .isInstanceOf(ExecutionException.class)
                .hasCause(boom);
    }

    /** Both executors Boot builds, with the caller's observation carried over as Boot does it. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recordsTheFailureOfATaskSubmittedToSpringsExecutors(boolean threadPool) throws Exception {
        RuntimeException boom = new RuntimeException("boom");
        AsyncTaskExecutor executor = executorDecoratedAsBootDoes(threadPool);

        try {
            Future<Object> future = runWithParentObservationInScope(() -> executor.submit(() -> {
                throw boom;
            }));

            assertThatThrownBy(future::get).hasCause(boom);
            assertThat(stoppedAsyncTask().getError()).isSameAs(boom);
        } finally {
            if (executor instanceof ThreadPoolTaskExecutor pool) {
                pool.shutdown();
            }
        }
    }

    @Test
    void recordsNothingForASubmittedTaskThatSucceeds() {
        FutureTask<String> submitted = new FutureTask<>(() -> "enriched");

        runWithParentObservationInScope(decorator.decorate(submitted));

        assertThat(asyncContexts())
                .singleElement()
                .satisfies(task -> assertThat(task.getError()).isNull());
    }

    /** Cancelling is the submitter's decision, not a failure of the task. */
    @Test
    void recordsNothingForASubmittedTaskCancelledBeforeItRan() {
        FutureTask<String> submitted = new FutureTask<>(() -> "enriched");
        submitted.cancel(false);

        runWithParentObservationInScope(decorator.decorate(submitted));

        assertThat(asyncContexts())
                .singleElement()
                .satisfies(task -> assertThat(task.getError()).isNull());
    }

    /**
     * Spring rethrows a failing Future-returning @Async method's exception into the FutureTask
     * after the failure interceptor has already recorded it on the span.
     */
    @Test
    void recordsAFailureTheTaskAlreadyRecordedOnlyOnce() {
        RuntimeException boom = new RuntimeException("boom");
        FutureTask<Object> submitted = new FutureTask<>(() -> {
            RunningAsyncTask.recordFailure(boom);
            throw boom;
        });

        runWithParentObservationInScope(decorator.decorate(submitted));

        assertThat(asyncContexts())
                .singleElement()
                .satisfies(task -> assertThat(task.getError()).isSameAs(boom));
        assertThat(errorsRecorded).hasValue(1);
    }

    private void runWithParentObservationInScope(Runnable task) {
        runWithParentObservationInScope(() -> {
            task.run();
            return null;
        });
    }

    private <T> T runWithParentObservationInScope(Supplier<T> call) {
        Observation parent = Observation.createNotStarted("parent", registry).start();
        try (Observation.Scope scope = parent.openScope()) {
            return call.get();
        } finally {
            parent.stop();
        }
    }

    private AsyncTaskExecutor executorDecoratedAsBootDoes(boolean threadPool) {
        TaskDecorator decorators = new CompositeTaskDecorator(
                List.<TaskDecorator>of(decorator, task -> continueObservation(registry.getCurrentObservation(), task)));
        if (!threadPool) {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
            executor.setTaskDecorator(decorators);
            return executor;
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(decorators);
        executor.initialize();
        return executor;
    }

    private static Runnable continueObservation(Observation submitting, Runnable task) {
        return submitting == null ? task : () -> submitting.scoped(task);
    }

    /** The task's observation stops after the caller's future completes, so wait for it. */
    private Observation.Context stoppedAsyncTask() throws InterruptedException {
        while (true) {
            Observation.Context context = stopped.poll(10, TimeUnit.SECONDS);
            assertThat(context).as("an async task observation stopped").isNotNull();
            if (AsyncTaskMarker.OBSERVATION_NAME.equals(context.getName())) {
                return context;
            }
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
