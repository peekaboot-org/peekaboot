package org.peekaboot.backend.tracing.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.Observation;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.testsupport.RecordingObservations;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.support.DelegatingErrorHandlingRunnable;
import org.springframework.util.ErrorHandler;

class AsyncTaskDecoratorTest {

    private final RecordingObservations observations = new RecordingObservations();
    private final AsyncTaskDecorator decorator = new AsyncTaskDecorator(observations.registry());

    @Test
    void observesATaskDispatchedWithAnObservationInScope() {
        AtomicInteger runs = new AtomicInteger();
        Runnable decorated = decorator.decorate(runs::incrementAndGet);

        observations.runInsideParentObservation(decorated);

        assertThat(runs).hasValue(1);
        assertThat(asyncContexts()).hasSize(1);
        assertThat(asyncContexts()
                        .get(0)
                        .getLowCardinalityKeyValue(AsyncTaskMarker.TAG_KEY)
                        .getValue())
                .isEqualTo(AsyncTaskMarker.TAG_VALUE);
    }

    @Test
    void namesTheThreadTheTaskRanOn() {
        Runnable decorated = decorator.decorate(() -> {});

        observations.runInsideParentObservation(decorated);

        assertThat(asyncContexts()
                        .get(0)
                        .getHighCardinalityKeyValue(AsyncTaskMarker.THREAD_TAG_KEY)
                        .getValue())
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

        assertThatThrownBy(() -> observations.runInsideParentObservation(decorated))
                .isSameAs(boom);

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

        observations.runInsideParentObservation(decorator.decorate(submitted));

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
            Future<Object> future = observations.insideParentObservation(() -> executor.submit(() -> {
                throw boom;
            }));

            assertThatThrownBy(future::get).hasCause(boom);
            assertThat(observations
                            .awaitStopped(AsyncTaskMarker.OBSERVATION_NAME)
                            .getError())
                    .isSameAs(boom);
        } finally {
            if (executor instanceof ThreadPoolTaskExecutor pool) {
                pool.shutdown();
            }
        }
    }

    @Test
    void recordsNothingForASubmittedTaskThatSucceeds() {
        FutureTask<String> submitted = new FutureTask<>(() -> "enriched");

        observations.runInsideParentObservation(decorator.decorate(submitted));

        assertThat(asyncContexts())
                .singleElement()
                .satisfies(task -> assertThat(task.getError()).isNull());
    }

    /** Cancelling is the submitter's decision, not a failure of the task. */
    @Test
    void recordsNothingForASubmittedTaskCancelledBeforeItRan() {
        FutureTask<String> submitted = new FutureTask<>(() -> "enriched");
        submitted.cancel(false);

        observations.runInsideParentObservation(decorator.decorate(submitted));

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

        observations.runInsideParentObservation(decorator.decorate(submitted));

        assertThat(asyncContexts())
                .singleElement()
                .satisfies(task -> assertThat(task.getError()).isSameAs(boom));
        assertThat(observations.erroredNamed(AsyncTaskMarker.OBSERVATION_NAME)).hasSize(1);
    }

    private AsyncTaskExecutor executorDecoratedAsBootDoes(boolean threadPool) {
        TaskDecorator decorators =
                new CompositeTaskDecorator(List.<TaskDecorator>of(decorator, new ContextPropagatingTaskDecorator()));
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

    private List<Observation.Context> asyncContexts() {
        return observations.startedNamed(AsyncTaskMarker.OBSERVATION_NAME);
    }
}
