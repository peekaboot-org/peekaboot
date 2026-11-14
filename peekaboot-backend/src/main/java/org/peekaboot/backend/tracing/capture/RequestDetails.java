package org.peekaboot.backend.tracing.capture;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TagMasker;

/** The headers and parameters of a request as a trace carries them, masked. */
final class RequestDetails {

    /** A key sent in both the query string and the body lists its query values and is absent from {@code form}. */
    record Parameters(Map<String, List<String>> query, Map<String, List<String>> form) {}

    private final MaskingEngine maskingEngine;
    private final TagMasker tagMasker;

    RequestDetails(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
        this.tagMasker = new TagMasker(maskingEngine);
    }

    Map<String, String> requestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(name, String.join(", ", Collections.list(request.getHeaders(name)))));
        return tagMasker.mask(headers);
    }

    /** A header sent more than once (Set-Cookie, typically) is captured as one comma-joined value. */
    Map<String, String> responseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new HashMap<>();
        response.getHeaderNames().forEach(name -> headers.put(name, String.join(", ", response.getHeaders(name))));
        return tagMasker.mask(headers);
    }

    /**
     * The container parses a form body on the first read of a parameter, so the body's
     * parameters are read only when the application read them. Query parameters come from the
     * query string, which costs no container call.
     */
    Parameters parameters(ParameterReads request) {
        Map<String, List<String>> query = queryParameters(request.getQueryString());
        Map<String, List<String>> form = request.parametersRead() ? formParameters(request, query.keySet()) : Map.of();
        return new Parameters(query, form);
    }

    private Map<String, List<String>> queryParameters(String queryString) {
        Map<String, List<String>> parameters = new HashMap<>();
        if (queryString == null || queryString.isBlank()) {
            return parameters;
        }
        for (String pair : queryString.split("&", -1)) {
            int equalsIndex = pair.indexOf('=');
            String rawKey = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            if (!rawKey.isBlank()) {
                String rawValue = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
                addQueryParameter(parameters, rawKey, rawValue);
            }
        }
        return parameters;
    }

    private void addQueryParameter(Map<String, List<String>> parameters, String rawKey, String rawValue) {
        String key;
        String value;
        try {
            key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            value = maskingEngine.mask(key, URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformedEncoding) {
            // cannot be judged, so it is masked under its raw key, as maskQueryString masks it
            key = rawKey;
            value = MaskingEngine.MASK_LITERAL;
        }
        parameters.computeIfAbsent(key, absent -> new ArrayList<>()).add(value);
    }

    /**
     * getParameterMap() merges query-string and body parameters (form-encoded and multipart
     * alike); a parameter the query string does not carry came from the body.
     */
    private Map<String, List<String>> formParameters(HttpServletRequest request, Set<String> queryKeys) {
        Map<String, List<String>> parameters = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values == null || values.length == 0 || queryKeys.contains(key)) {
                return;
            }
            List<String> maskedValues =
                    Arrays.stream(values).map(v -> maskingEngine.mask(key, v)).toList();
            parameters.put(key, maskedValues);
        });
        return parameters;
    }
}
