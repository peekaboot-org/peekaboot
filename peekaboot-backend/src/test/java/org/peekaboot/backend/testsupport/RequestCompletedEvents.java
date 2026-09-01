package org.peekaboot.backend.testsupport;

import java.util.List;
import java.util.Map;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;

/**
 * Builds {@link RequestCompletedEvent} fixtures: a {@code GET /} that answered 200 with no
 * headers, parameters, body or controller unless a test says otherwise. Collections default
 * to empty, and can be set to {@code null} explicitly to exercise the null-tolerant readers.
 */
public final class RequestCompletedEvents {

    private RequestCompletedEvents() {}

    public static RequestCompletedEvent minimal(String traceId) {
        return request(traceId).build();
    }

    public static Builder request(String traceId) {
        return new Builder(traceId);
    }

    public static final class Builder {

        private final String traceId;
        private String method = "GET";
        private String path = "/";
        private String queryString;
        private Map<String, String> requestHeaders = Map.of();
        private String requestBody;
        private boolean requestBodyTruncated;
        private String controllerClass;
        private String controllerMethod;
        private Map<String, List<String>> queryParams = Map.of();
        private Map<String, List<String>> formParams = Map.of();
        private List<RequestCompletedEvent.UploadedFile> uploadedFiles = List.of();
        private int status = 200;
        private Map<String, String> responseHeaders = Map.of();
        private long durationMs;

        private Builder(String traceId) {
            this.traceId = traceId;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder queryString(String queryString) {
            this.queryString = queryString;
            return this;
        }

        public Builder requestHeaders(Map<String, String> requestHeaders) {
            this.requestHeaders = requestHeaders;
            return this;
        }

        public Builder requestBody(String requestBody, boolean truncated) {
            this.requestBody = requestBody;
            this.requestBodyTruncated = truncated;
            return this;
        }

        public Builder controller(String controllerClass, String controllerMethod) {
            this.controllerClass = controllerClass;
            this.controllerMethod = controllerMethod;
            return this;
        }

        public Builder queryParams(Map<String, List<String>> queryParams) {
            this.queryParams = queryParams;
            return this;
        }

        public Builder formParams(Map<String, List<String>> formParams) {
            this.formParams = formParams;
            return this;
        }

        public Builder uploadedFiles(List<RequestCompletedEvent.UploadedFile> uploadedFiles) {
            this.uploadedFiles = uploadedFiles;
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder responseHeaders(Map<String, String> responseHeaders) {
            this.responseHeaders = responseHeaders;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public RequestCompletedEvent build() {
            return new RequestCompletedEvent(
                    traceId,
                    method,
                    path,
                    queryString,
                    requestHeaders,
                    requestBody,
                    requestBodyTruncated,
                    controllerClass,
                    controllerMethod,
                    queryParams,
                    formParams,
                    uploadedFiles,
                    status,
                    responseHeaders,
                    durationMs);
        }
    }
}
