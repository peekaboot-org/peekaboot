package net.osslabz.peekaboot.tracing.context;

import io.micrometer.tracing.CurrentTraceContext;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class WrappedExecutorService implements ExecutorService {

    private final ExecutorService delegate;
    private final CurrentTraceContext currentTraceContext;

    WrappedExecutorService(ExecutorService delegate, CurrentTraceContext currentTraceContext) {
        this.delegate = delegate;
        this.currentTraceContext = currentTraceContext;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(currentTraceContext.wrap(command));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(currentTraceContext.wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(currentTraceContext.wrap(task), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(currentTraceContext.wrap(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapAll(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapAll(tasks), timeout, unit);
    }

    private <T> Collection<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        return tasks.stream()
                .map(currentTraceContext::wrap)
                .toList();
    }
}
