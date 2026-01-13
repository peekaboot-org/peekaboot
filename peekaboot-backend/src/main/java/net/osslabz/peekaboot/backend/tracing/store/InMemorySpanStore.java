package net.osslabz.peekaboot.backend.tracing.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class InMemorySpanStore {

    private final Cache<String, CopyOnWriteArrayList<SpanData>> traceCache;
    private final Cache<String, Long> traceCreationTimes;
    private final int maxSpansPerTrace;
    private final AtomicLong spanCounter = new AtomicLong(0);

    public InMemorySpanStore(int maxTraces, int maxSpansPerTrace) {
        this.maxSpansPerTrace = maxSpansPerTrace;

        this.traceCreationTimes = Caffeine.newBuilder()
                .maximumSize(maxTraces)
                .build();

        this.traceCache = Caffeine.newBuilder()
                .maximumSize(maxTraces)
                .expireAfterWrite(Duration.ofHours(1))
                .<String, CopyOnWriteArrayList<SpanData>>evictionListener((key, value, cause) -> {
                    if (key != null) {
                        traceCreationTimes.invalidate(key);
                    }
                })
                .build();
    }

    public void report(SpanData spanData) {
        String traceId = spanData.traceId();

        traceCreationTimes.get(traceId, k -> System.currentTimeMillis());

        CopyOnWriteArrayList<SpanData> spans = traceCache.get(traceId, k -> new CopyOnWriteArrayList<>());
        if (spans != null) {
            spans.add(spanData);

            if (spans.size() > maxSpansPerTrace) {
                spans.subList(0, spans.size() - maxSpansPerTrace).clear();
            }
        }
    }

    public long nextCreationOrder() {
        return spanCounter.incrementAndGet();
    }

    public List<SpanData> getSpansForTrace(String traceId) {
        CopyOnWriteArrayList<SpanData> spans = traceCache.getIfPresent(traceId);
        if (spans == null) {
            return List.of();
        }
        return spans.stream()
                .sorted(Comparator.comparingLong(SpanData::creationOrder))
                .toList();
    }

    public Optional<TraceData> getTrace(String traceId) {
        List<SpanData> spans = getSpansForTrace(traceId);
        if (spans.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(TraceData.fromSpans(traceId, spans));
    }

    public List<TraceData> getRecentTraces(int limit) {
        return traceCreationTimes.asMap().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(entry -> getTrace(entry.getKey()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    public List<TraceData> getAllTraces() {
        return traceCreationTimes.asMap().keySet().stream()
                .map(this::getTrace)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(TraceData::startTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public int getTraceCount() {
        return (int) traceCache.estimatedSize();
    }

    public void clear() {
        traceCache.invalidateAll();
        traceCreationTimes.invalidateAll();
    }
}
