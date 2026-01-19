package net.osslabz.peekaboot.backend.tracing.event;

import java.util.List;
import java.util.Map;

public record RequestCompletedEvent(
        String traceId,
        // Request
        String method,
        String path,
        String queryString,
        Map<String, String> requestHeaders,
        String requestBody,
        boolean requestBodyTruncated,
        String controllerClass,
        String controllerMethod,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> formParams,
        List<UploadedFile> uploadedFiles,
        // Response
        int status,
        Map<String, String> responseHeaders,
        // Timing
        long durationMs
) implements TraceDataEvent {

    public record UploadedFile(
            String fieldName,
            String originalFilename,
            String contentType,
            long size
    ) {}
}
