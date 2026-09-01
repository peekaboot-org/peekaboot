package org.peekaboot.backend.mapper.trace;

import java.util.Map;

/**
 * Reads an HTTP span's method, path and status whichever convention named its tags.
 *
 * <p>Three naming schemes reach the store. Spring Boot's default server-request observation
 * ({@code DefaultServerRequestObservationConvention}) tags {@code method}, {@code status},
 * {@code uri} - the matched route pattern, {@code /users/{id}} - and {@code http.url}, which
 * despite its name is the request URI, so it is the path to show and {@code uri} only the
 * fallback. The current OpenTelemetry semantic conventions (Spring 7's opt-in server
 * convention, and most client instrumentations) tag {@code http.request.method},
 * {@code url.path} and {@code http.response.status_code}; their superseded spelling is
 * {@code http.method}, {@code http.target} and {@code http.status_code}. The OpenTelemetry
 * names are more specific than Spring's, so they win where a span carries both.
 */
public final class HttpSpanTags {

    private static final String[] METHOD_KEYS = {"http.request.method", "http.method", "method"};
    private static final String[] PATH_KEYS = {"url.path", "http.target", "http.url", "uri"};
    private static final String[] STATUS_KEYS = {"http.response.status_code", "http.status_code", "status"};

    private HttpSpanTags() {}

    public static String method(Map<String, String> tags) {
        return first(tags, METHOD_KEYS);
    }

    /** The request path, without scheme, authority or query string whichever tag supplied it. */
    public static String path(Map<String, String> tags) {
        String value = first(tags, PATH_KEYS);
        return value == null ? null : pathOf(value);
    }

    public static Integer statusCode(Map<String, String> tags) {
        String value = first(tags, STATUS_KEYS);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether the span carries HTTP tags at all: any {@code http.*} key, or Spring's
     * unprefixed {@code method} together with {@code uri} - the pair no other Spring
     * convention emits, which keeps an RPC span's bare {@code method} from counting.
     */
    public static boolean describeHttpRequest(Map<String, String> tags) {
        if (tags.keySet().stream().anyMatch(key -> key.startsWith("http."))) {
            return true;
        }
        return tags.containsKey("method") && tags.containsKey("uri");
    }

    private static String first(Map<String, String> tags, String... keys) {
        for (String key : keys) {
            String value = tags.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String pathOf(String urlOrPath) {
        String path = urlOrPath;
        int schemeEnd = path.indexOf("://");
        if (schemeEnd >= 0) {
            int pathStart = path.indexOf('/', schemeEnd + 3);
            path = pathStart < 0 ? "/" : path.substring(pathStart);
        }
        int queryStart = path.indexOf('?');
        return queryStart < 0 ? path : path.substring(0, queryStart);
    }
}
