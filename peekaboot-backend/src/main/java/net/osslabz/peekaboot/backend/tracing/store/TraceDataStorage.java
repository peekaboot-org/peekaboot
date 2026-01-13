package net.osslabz.peekaboot.backend.tracing.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.osslabz.peekaboot.backend.tracing.event.LogCapturedEvent;
import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.event.SpanCompletedEvent;
import net.osslabz.peekaboot.backend.tracing.event.TraceDataEvent;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class TraceDataStorage implements Consumer<TraceDataEvent> {

    private static final int DEFAULT_MAX_TRACES = 1000;
    private static final Duration DEFAULT_EXPIRE = Duration.ofMinutes(30);

    private final Cache<String, TraceDataBundle> cache;

    public TraceDataStorage() {
        this(DEFAULT_MAX_TRACES, DEFAULT_EXPIRE);
    }

    public TraceDataStorage(int maxTraces, Duration expireAfter) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxTraces)
                .expireAfterWrite(expireAfter)
                .build();
    }

    @Override
    public void accept(TraceDataEvent event) {
        if (event == null) {
            return;
        }

        TraceDataBundle bundle = cache.get(event.traceId(), TraceDataBundle::new);
        if (bundle == null) {
            return;
        }

        switch (event) {
            case SpanCompletedEvent e -> bundle.addSpan(e);
            case LogCapturedEvent e -> bundle.addLog(e);
            case RequestCompletedEvent e -> bundle.setRequest(e);
        }
    }

    public Optional<TraceDataBundle> getTrace(String traceId) {
        return Optional.ofNullable(cache.getIfPresent(traceId));
    }

    public List<TraceDataBundle> getRecentTraces(int limit) {
        return cache.asMap().values().stream()
                .sorted(Comparator.comparingLong(TraceDataBundle::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    public int getTraceCount() {
        return (int) cache.estimatedSize();
    }

    public void clear() {
        cache.invalidateAll();
    }
}
