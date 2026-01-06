package net.osslabz.peekaboot.backend.devtoolbar;

import net.osslabz.peekaboot.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ToolbarDataProvider {

    private final TraceQueryService traceQueryService;
    private final String basePath;

    public ToolbarDataProvider(TraceQueryService traceQueryService, String basePath) {
        this.traceQueryService = traceQueryService;
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

        return String.format(
                "{\"method\":\"%s\",\"path\":\"%s\",\"status\":%d,\"traceId\":%s,\"dashboardUrl\":\"%s\",\"duration\":%d,\"queryCount\":%d,\"errorCount\":%d}",
                escapeJson(method),
                escapeJson(path),
                status,
                traceId != null ? "\"" + escapeJson(traceId) + "\"" : "null",
                basePath + "/",
                duration,
                queryCount,
                errorCount
        );
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
