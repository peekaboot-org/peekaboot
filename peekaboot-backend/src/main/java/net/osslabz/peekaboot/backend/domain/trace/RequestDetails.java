package net.osslabz.peekaboot.backend.domain.trace;

import java.util.Map;

public record RequestDetails(
        String controllerClass,
        String controllerMethod,
        Map<String, String> requestHeaders,
        Map<String, String> responseHeaders,
        Map<String, String> queryParams,
        Map<String, String> formParams,
        String requestBody,
        boolean requestBodyTruncated
) {
}
