package org.peekaboot.backend.tracing.async;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;

/**
 * Records an {@code @Async} method's failure on the {@code async task} span it runs in. Spring's
 * async interceptor catches that failure inside its own task, so {@link AsyncTaskDecorator}
 * never sees it; this runs one step further in, on the executor thread. The failure reaches
 * Spring unchanged, so the application's uncaught-exception handler or the caller's future gets
 * it as before.
 */
final class AsyncTaskFailureInterceptor implements MethodInterceptor {

    // proceed() declares Throwable, and every failure is rethrown unchanged
    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        try {
            Object result = invocation.proceed();
            if (result instanceof Future<?> future) {
                recordFailureOf(future);
            }
            return result;
        } catch (Throwable failure) {
            RunningAsyncTask.recordFailure(failure);
            throw failure;
        }
    }

    /** Spring waits on the same future right after this returns, so waiting here first delays nothing. */
    private static void recordFailureOf(Future<?> future) throws InterruptedException {
        try {
            future.get();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            RunningAsyncTask.recordFailure(cause == null ? failed : cause);
        }
    }
}
