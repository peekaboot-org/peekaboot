package net.osslabz.peekaboot.backend.log;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TraceLogStore {

    private static final int MAX_TRACES = 1000;
    private static final int MAX_LOGS_PER_TRACE = 500;
    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofMinutes(30);

    private final Cache<String, List<LogEntry>> logsByTraceId;

    public TraceLogStore() {
        this.logsByTraceId = Caffeine.newBuilder()
                .maximumSize(MAX_TRACES)
                .expireAfterWrite(EXPIRE_AFTER_WRITE)
                .build();
    }

    public void addLog(LogEntry entry) {
        if (entry.traceId() == null || entry.traceId().isBlank()) {
            return;
        }

        logsByTraceId.asMap().compute(entry.traceId(), (traceId, existing) -> {
            List<LogEntry> logs = existing != null ? existing : new CopyOnWriteArrayList<>();
            if (logs.size() < MAX_LOGS_PER_TRACE) {
                logs.add(entry);
            }
            return logs;
        });
    }

    public List<LogEntry> getLogsForTrace(String traceId) {
        if (traceId == null) {
            return Collections.emptyList();
        }
        List<LogEntry> logs = logsByTraceId.getIfPresent(traceId);
        return logs != null ? new ArrayList<>(logs) : Collections.emptyList();
    }

    public void evictTrace(String traceId) {
        if (traceId != null) {
            logsByTraceId.invalidate(traceId);
        }
    }

    public int getTraceCount() {
        return (int) logsByTraceId.estimatedSize();
    }
}
