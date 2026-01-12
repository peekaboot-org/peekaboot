package net.osslabz.peekaboot.tracing.event;

import java.util.Map;

public record RequestCompletedEvent(
        String traceId,
        String method,
        String path,
        int status,
        long durationMs,
        Map<String, String> requestHeaders,
        Map<String, String> responseHeaders,
        Map<String, String> queryParams,
        String controllerClass,
        String controllerMethod,
        String requestBody,
        boolean requestBodyTruncated
) implements TraceDataEvent {
}
