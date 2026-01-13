package net.osslabz.peekaboot.backend.devtoolbar;

import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ToolbarDataProvider {

    private final TraceQueryService traceQueryService;
    private final PeekabookActuatorService actuatorService;
    private final String basePath;

    public ToolbarDataProvider(TraceQueryService traceQueryService,
                               PeekabookActuatorService actuatorService,
                               String basePath) {
        this.traceQueryService = traceQueryService;
        this.actuatorService = actuatorService;
        this.basePath = basePath;
    }

    public String getToolbarSummaryJson(String method, String path, int status, String traceId) {
        long duration = 0;
        int queryCount = -1;
        int errorCount = 0;

        if (traceId != null && traceQueryService != null) {
            Optional<TraceData> traceOpt = traceQueryService.getTrace(traceId);
            if (traceOpt.isPresent()) {
                TraceData trace = traceOpt.get();
                Duration traceDuration = trace.duration();
                if (traceDuration != null) {
                    duration = traceDuration.toMillis();
                }

                List<SpanData> spans = trace.spans();
                if (spans != null) {
                    queryCount = countDatabaseQueries(spans);
                    errorCount = countErrors(spans);
                }
            }
        }

        // Get health and memory from actuator
        String healthStatus = "UNKNOWN";
        int memoryPercent = -1;

        if (actuatorService != null) {
            try {
                Map<String, Object> data = actuatorService.getData();
                healthStatus = extractHealthStatus(data);
                memoryPercent = extractMemoryPercent(data);
            } catch (Exception e) {
                // Ignore - use defaults
            }
        }

        return String.format(
                "{\"method\":\"%s\",\"path\":\"%s\",\"status\":%d,\"traceId\":%s,\"dashboardUrl\":\"%s\",\"duration\":%d,\"queryCount\":%d,\"errorCount\":%d,\"health\":\"%s\",\"memoryPercent\":%d}",
                escapeJson(method),
                escapeJson(path),
                status,
                traceId != null ? "\"" + escapeJson(traceId) + "\"" : "null",
                basePath + "/",
                duration,
                queryCount,
                errorCount,
                escapeJson(healthStatus),
                memoryPercent
        );
    }

    @SuppressWarnings("unchecked")
    private String extractHealthStatus(Map<String, Object> data) {
        try {
            Map<String, Object> health = (Map<String, Object>) data.get("health");
            if (health != null) {
                Object status = health.get("status");
                if (status != null) {
                    return status.toString();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return "UNKNOWN";
    }

    @SuppressWarnings("unchecked")
    private int extractMemoryPercent(Map<String, Object> data) {
        try {
            Map<String, Object> info = (Map<String, Object>) data.get("info");
            if (info != null) {
                Map<String, Object> process = (Map<String, Object>) info.get("process");
                if (process != null) {
                    Map<String, Object> memory = (Map<String, Object>) process.get("memory");
                    if (memory != null) {
                        Map<String, Object> heap = (Map<String, Object>) memory.get("heap");
                        if (heap != null) {
                            Number used = (Number) heap.get("used");
                            Number max = (Number) heap.get("max");
                            if (used != null && max != null && max.longValue() > 0) {
                                return (int) ((used.longValue() * 100) / max.longValue());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private int countDatabaseQueries(List<SpanData> spans) {
        int count = 0;
        for (SpanData span : spans) {
            String name = span.name();
            if (name != null && (
                    name.startsWith("query") ||
                    name.startsWith("SELECT") ||
                    name.startsWith("INSERT") ||
                    name.startsWith("UPDATE") ||
                    name.startsWith("DELETE") ||
                    name.contains("jdbc") ||
                    name.contains("sql"))) {
                count++;
            }
            // Also check for db.system tag
            Map<String, String> tags = span.tags();
            if (tags != null && tags.containsKey("db.system")) {
                count++;
            }
        }
        return count;
    }

    private int countErrors(List<SpanData> spans) {
        int count = 0;
        for (SpanData span : spans) {
            if (span.errorMessage() != null || span.errorClass() != null) {
                count++;
            }
            Map<String, String> tags = span.tags();
            if (tags != null && "true".equals(tags.get("error"))) {
                count++;
            }
        }
        return count;
    }
}
