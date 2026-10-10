package org.peekaboot.backend.tracing.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.micrometer.observation.Observation;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.peekaboot.backend.testsupport.RecordingObservations;
import org.springframework.aop.framework.ProxyFactory;

/**
 * Each task runs its work the way Spring's async interceptor does: inside a decorated task, with
 * the work's exception caught before it reaches the decorator.
 */
class AsyncTaskFailureInterceptorTest {

    private final RecordingObservations observations = new RecordingObservations();
    private final AsyncTaskDecorator decorator = new AsyncTaskDecorator(observations.registry());

    @Test
    void recordsAThrownExceptionOnTheRunningTaskAndRethrowsIt() throws Exception {
        IllegalStateException rejected = new IllegalStateException("rejected");

        Throwable thrown = runAsDecoratedTask(intercepted(() -> {
            throw rejected;
        }));

        assertThat(thrown).isSameAs(rejected);
        assertThat(asyncTaskContext().getError()).isSameAs(rejected);
    }

    @Test
    void recordsTheCauseOfAFailedReturnedFutureAndReturnsTheSameFuture() throws Exception {
        IllegalStateException rejected = new IllegalStateException("rejected");
        CompletableFuture<String> failed = CompletableFuture.failedFuture(rejected);
        AtomicReference<@Nullable Object> returned = new AtomicReference<>();
        AsyncWork work = intercepted(() -> failed);

        Throwable thrown = runAsDecoratedTask(() -> {
            returned.set(work.perform());
            return null;
        });

        assertThat(thrown).isNull();
        assertThat(returned).hasValue(failed);
        assertThat(asyncTaskContext().getError()).isSameAs(rejected);
    }

    /** An @Async method may hand back a future that some other thread fails after the method returned. */
    @Test
    void recordsAReturnedFutureThatFailsAfterTheMethodReturned() throws Exception {
        IllegalStateException rejected = new IllegalStateException("rejected");
        CompletableFuture<String> pending = new CompletableFuture<>();
        Executor later = CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS);
        later.execute(() -> pending.completeExceptionally(rejected));

        runAsDecoratedTask(intercepted(() -> pending));

        assertThat(asyncTaskContext().getError()).isSameAs(rejected);
    }

    @Test
    void recordsNothingWhenTheWorkSucceeds() throws Exception {
        CompletableFuture<String> enriched = CompletableFuture.completedFuture("enriched");

        runAsDecoratedTask(intercepted(() -> "enriched"));
        runAsDecoratedTask(intercepted(() -> enriched));

        assertThat(asyncTaskContexts())
                .hasSize(2)
                .allSatisfy(context -> assertThat(context.getError()).isNull());
    }

    @Test
    void recordsNothingAndRethrowsWhenNoDecoratedTaskIsRunning() throws Exception {
        IllegalStateException rejected = new IllegalStateException("rejected");
        AsyncWork work = intercepted(() -> {
            throw rejected;
        });

        Throwable thrown = observations.insideParentObservation(() -> catchThrowable(work::perform));

        assertThat(thrown).isSameAs(rejected);
        assertThat(observations.startedNamed("parent"))
                .singleElement()
                .satisfies(context -> assertThat(context.getError()).isNull());
    }

    /**
     * An executor Peekaboot does not decorate can still propagate an async task's observation to
     * its own thread; work failing there is not that task's failure.
     */
    @Test
    void leavesAnAsyncTaskPropagatedToAnotherThreadAlone() throws Exception {
        IllegalStateException rejected = new IllegalStateException("rejected elsewhere");
        AsyncWork work = intercepted(() -> {
            throw rejected;
        });
        AtomicReference<@Nullable Throwable> thrownElsewhere = new AtomicReference<>();

        runAsDecoratedTask(() -> {
            Observation asyncTask =
                    Objects.requireNonNull(observations.registry().getCurrentObservation());
            Thread undecorated =
                    new Thread(() -> asyncTask.scoped(() -> thrownElsewhere.set(catchThrowable(work::perform))));
            undecorated.start();
            undecorated.join();
            return null;
        });

        assertThat(thrownElsewhere).hasValue(rejected);
        assertThat(asyncTaskContext().getError()).isNull();
    }

    /** A rejected task under CallerRunsPolicy runs nested inside the caller's own decorated task, on its thread. */
    @Test
    void recordsEachFailureOnItsOwnTaskWhenDecoratedTasksNest() throws Exception {
        IllegalStateException innerFailure = new IllegalStateException("inner");
        IllegalStateException outerFailure = new IllegalStateException("outer");
        AsyncWork failingInner = intercepted(() -> {
            throw innerFailure;
        });
        AsyncWork failingOuter = intercepted(() -> {
            throw outerFailure;
        });

        AtomicReference<@Nullable Throwable> thrownInside = new AtomicReference<>();

        Throwable thrownOutside = runAsDecoratedTask(() -> {
            decorator
                    .decorate(() -> thrownInside.set(catchThrowable(failingInner::perform)))
                    .run();
            return failingOuter.perform();
        });

        assertThat(thrownInside).hasValue(innerFailure);
        assertThat(thrownOutside).isSameAs(outerFailure);

        List<Observation.Context> tasks = asyncTaskContexts();
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).getError()).as("the outer task").isSameAs(outerFailure);
        assertThat(tasks.get(1).getError()).as("the inner task").isSameAs(innerFailure);
    }

    /**
     * Runs the work as Spring's async interceptor would: in a task handed to the decorator while
     * a trace is in scope, with any exception caught inside the task.
     *
     * @return what the work threw, or {@code null}
     */
    private @Nullable Throwable runAsDecoratedTask(AsyncWork work) throws Exception {
        AtomicReference<@Nullable Throwable> thrown = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> thrown.set(catchThrowable(work::perform)));
        observations.insideParentObservation(() -> {
            task.run();
            return null;
        });
        return thrown.get();
    }

    private AsyncWork intercepted(AsyncWork work) {
        ProxyFactory proxyFactory = new ProxyFactory(work);
        proxyFactory.addInterface(AsyncWork.class);
        proxyFactory.addAdvice(new AsyncTaskFailureInterceptor());
        return (AsyncWork) proxyFactory.getProxy();
    }

    private Observation.Context asyncTaskContext() {
        assertThat(asyncTaskContexts()).hasSize(1);
        return asyncTaskContexts().getFirst();
    }

    private List<Observation.Context> asyncTaskContexts() {
        return observations.startedNamed(AsyncTaskMarker.OBSERVATION_NAME);
    }

    /** Stands in for an {@code @Async} method of any return type. */
    @FunctionalInterface
    interface AsyncWork {
        @Nullable
        Object perform() throws Exception;
    }
}
