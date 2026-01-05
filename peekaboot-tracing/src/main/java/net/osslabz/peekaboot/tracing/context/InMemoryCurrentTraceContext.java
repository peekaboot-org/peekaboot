package net.osslabz.peekaboot.tracing.context;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

public class InMemoryCurrentTraceContext implements CurrentTraceContext {

    private final ThreadLocal<TraceContext> current = new ThreadLocal<>();

    @Override
    public TraceContext context() {
        return current.get();
    }

    @Override
    public Scope newScope(TraceContext context) {
        TraceContext previous = current.get();
        current.set(context);
        InMemoryScope.updateMdc(context);
        return new InMemoryScope(this, previous);
    }

    @Override
    public Scope maybeScope(TraceContext context) {
        TraceContext current = context();
        if (Objects.equals(current, context)) {
            return Scope.NOOP;
        }
        return newScope(context);
    }

    void restore(TraceContext context) {
        current.set(context);
    }

    @Override
    public <C> Callable<C> wrap(Callable<C> task) {
        TraceContext captured = context();
        return () -> {
            try (Scope ignored = newScope(captured)) {
                return task.call();
            }
        };
    }

    @Override
    public Runnable wrap(Runnable task) {
        TraceContext captured = context();
        return () -> {
            try (Scope ignored = newScope(captured)) {
                task.run();
            }
        };
    }

    @Override
    public Executor wrap(Executor delegate) {
        return command -> delegate.execute(wrap(command));
    }

    @Override
    public ExecutorService wrap(ExecutorService delegate) {
        return new WrappedExecutorService(delegate, this);
    }
}
