package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/**
 * Marks a response of Peekaboot's API as not to be stored and not to be content-sniffed. The
 * API serves environment variables, configuration values and captured request headers; a proxy,
 * CDN or the browser's back-forward cache in front of a staging box has no business keeping any
 * of it.
 */
public final class ApiSecurityHeaders {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private ApiSecurityHeaders() {}

    public static void apply(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(X_CONTENT_TYPE_OPTIONS, "nosniff");
    }
}
