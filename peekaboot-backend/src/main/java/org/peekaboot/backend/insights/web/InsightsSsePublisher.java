package org.peekaboot.backend.insights.web;

import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.insights.InsightsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fans collector events out to all connected dashboard SSE clients. */
public class InsightsSsePublisher implements InsightsCollector.Listener {

    private static final Logger log = LoggerFactory.getLogger(InsightsSsePublisher.class);

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private volatile Thread heartbeatThread;

    public InsightsSsePublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter subscribe() {
        // complete()/completeWithError() are overridden because outside a real
        // request dispatch (e.g. in unit tests, or when our own broadcast loop
        // calls them directly) Spring's container-driven onCompletion/onError
        // callbacks never fire - there is no Handler registered to invoke them.
        SseEmitter emitter = new SseEmitter(0L) {
            @Override
            public void complete() {
                super.complete();
                emitters.remove(this);
            }

            @Override
            public void completeWithError(Throwable ex) {
                super.completeWithError(ex);
                emitters.remove(this);
            }
        };
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        startHeartbeatIfNeeded();
        return emitter;
    }

    public int subscriberCount() {
        return emitters.size();
    }

    @Override
    public void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
        broadcast("tick", tickJson(epochMs, values, tiles));
    }

    @Override
    public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
        broadcast("rollup", rollupJson(level, epochMs, entries));
    }

    String tickJson(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("epochMs", epochMs);
        payload.put("values", nullSafeMap(values));
        payload.put("tiles", nullSafeMap(tiles));
        return objectMapper.writeValueAsString(payload);
    }

    String rollupJson(int level, long epochMs, Map<String, AggregateStats> entries) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("epochMs", epochMs);

        Map<String, Object> entryPayloads = new LinkedHashMap<>();
        for (Map.Entry<String, AggregateStats> entry : entries.entrySet()) {
            AggregateStats stats = entry.getValue();
            Map<String, Object> statsPayload = new LinkedHashMap<>();
            statsPayload.put("min", nullSafe(stats.min()));
            statsPayload.put("max", nullSafe(stats.max()));
            statsPayload.put("avg", nullSafe(stats.avg()));
            statsPayload.put("median", nullSafe(stats.median()));
            statsPayload.put("p90", nullSafe(stats.p90()));
            statsPayload.put("p95", nullSafe(stats.p95()));
            statsPayload.put("p99", nullSafe(stats.p99()));
            entryPayloads.put(entry.getKey(), statsPayload);
        }
        payload.put("entries", entryPayloads);

        return objectMapper.writeValueAsString(payload);
    }

    private static Map<String, Object> nullSafeMap(Map<String, Double> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            result.put(entry.getKey(), nullSafe(entry.getValue()));
        }
        return result;
    }

    private void broadcast(String eventName, String json) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping insights SSE subscriber after send failure", e);
                emitter.completeWithError(e);
            }
        }
    }

    private void startHeartbeatIfNeeded() {
        if (heartbeatThread != null) {
            return;
        }
        synchronized (this) {
            if (heartbeatThread != null) {
                return;
            }
            heartbeatThread = Thread.ofVirtual()
                    .name("peekaboot-insights-sse-heartbeat")
                    .unstarted(this::runHeartbeatLoop);
            heartbeatThread.start();
        }
    }

    private void runHeartbeatLoop() {
        try {
            while (!emitters.isEmpty()) {
                Thread.sleep(HEARTBEAT_INTERVAL);
                heartbeat();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                heartbeatThread = null;
            }
        }
    }

    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping insights SSE subscriber after heartbeat failure", e);
                emitter.completeWithError(e);
            }
        }
    }

    static Double nullSafe(double value) {
        return Double.isNaN(value) ? null : value;
    }
}
