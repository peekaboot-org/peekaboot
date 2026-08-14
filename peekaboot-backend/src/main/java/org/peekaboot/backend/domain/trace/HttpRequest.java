package org.peekaboot.backend.domain.trace;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record HttpRequest(
    String method,
    String path,
    String query,
    Map<String, String> headers,
    Body body,
    Controller controller,
    Params params
) {
    public record Body(
        boolean truncated,
        String content
    ) {}

    public record Controller(
        @JsonProperty("class") String className,
        String method
    ) {}

    public record Params(
        Map<String, List<String>> query,
        Map<String, List<String>> form,
        List<UploadedFile> upload
    ) {}

    public record UploadedFile(
        String fieldName,
        String originalFilename,
        String contentType,
        long size
    ) {}
}
