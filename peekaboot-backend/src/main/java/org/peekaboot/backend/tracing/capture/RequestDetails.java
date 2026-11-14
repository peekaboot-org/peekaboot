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
import org.peekaboot.backend.text.CountFormat;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/** A finished request as a trace carries it, each header and parameter name and value capped and then masked. */
final class RequestDetails {

    /** A key sent in both the query string and the body lists its query values and is absent from {@code form}. */
    private record Parameters(Map<String, List<String>> query, Map<String, List<String>> form) {}

    /**
     * Holds a Content-Security-Policy or a Referer whole. The masking patterns run on the
     * request thread, so a header or parameter reaches them no longer than this.
     */
    static final int MAX_VALUE_LENGTH = 4_096;

    private final MaskingEngine maskingEngine;
    private final TagMasker tagMasker;

    RequestDetails(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
        this.tagMasker = new TagMasker(maskingEngine);
    }

    /** The status is passed in: after a handler threw, the response does not carry it yet. */
    RequestCompletedEvent event(ParameterReads request, HttpServletResponse response, String traceId, int status) {
        Map<String, String> requestHeaders = requestHeaders(request);
        Map<String, String> responseHeaders = responseHeaders(response);
        Parameters parameters = parameters(request);

        String controllerClass = null;
        String controllerMethod = null;
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (handler instanceof HandlerMethod handlerMethod) {
            controllerClass = handlerMethod.getBeanType().getName();
            controllerMethod = handlerMethod.getMethod().getName();
        }

        return new RequestCompletedEvent(
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                maskingEngine.maskQueryString(request.getQueryString()),
                requestHeaders,
                controllerClass,
                controllerMethod,
                parameters.query(),
                parameters.form(),
                status,
                responseHeaders);
    }

    private Map<String, String> requestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(name -> headers.put(capped(name), cappedHeader(Collections.list(request.getHeaders(name)))));
        return tagMasker.mask(headers);
    }

    /** A header sent more than once (Set-Cookie, typically) is captured as one comma-joined value. */
    private Map<String, String> responseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new HashMap<>();
        response.getHeaderNames().forEach(name -> headers.put(capped(name), cappedHeader(response.getHeaders(name))));
        return tagMasker.mask(headers);
    }

    /**
     * The container parses a form body on the first read of a parameter, so the body's
     * parameters are read only when the application read them. Query parameters come from the
     * query string, which costs no container call.
     */
    private Parameters parameters(ParameterReads request) {
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
            key = capped(URLDecoder.decode(rawKey, StandardCharsets.UTF_8));
            value = maskingEngine.mask(key, capped(URLDecoder.decode(rawValue, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException malformedEncoding) {
            // cannot be judged, so it is masked under its raw key, as maskQueryString masks it
            key = capped(rawKey);
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
        request.getParameterMap().forEach((name, values) -> {
            String key = capped(name);
            if (values == null || values.length == 0 || queryKeys.contains(key)) {
                return;
            }
            List<String> maskedValues = Arrays.stream(values)
                    .map(v -> maskingEngine.mask(key, capped(v)))
                    .toList();
            parameters.put(key, maskedValues);
        });
        return parameters;
    }

    /** A repeated header is captured as one value, so its values are joined before the cut. */
    private static String cappedHeader(Iterable<String> values) {
        return capped(String.join(", ", values));
    }

    /**
     * A cut inside a token would show the head of a credential no pattern recognises any more.
     * So the text ends before its last whitespace within the limit, and is dropped whole
     * where it has none.
     */
    private static String capped(String text) {
        if (text == null || text.length() <= MAX_VALUE_LENGTH) {
            return text;
        }
        int end = MAX_VALUE_LENGTH;
        while (end > 0 && !Character.isWhitespace(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end) + "... " + CountFormat.of(text.length() - end, "character") + " omitted";
    }
}
