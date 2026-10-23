package org.peekaboot.backend.tracing.event;

import java.util.List;
import java.util.Map;

public record RequestCompletedEvent(
        String traceId,
        String method,
        String path,
        String queryString,
        Map<String, String> requestHeaders,
        String controllerClass,
        String controllerMethod,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> formParams,
        int status,
        Map<String, String> responseHeaders) {}
