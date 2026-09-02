package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Peekaboot's own URL space: the prefix its UI and API live under, and the prefixes its
 * filters, interceptor and span exporter leave alone.
 *
 * <p>Everything here is relative to the servlet context. Requests are matched on their
 * {@linkplain #pathWithinApplication path within the application}, which the container
 * has already decoded and normalised, so neither a {@code server.servlet.context-path}
 * nor a {@code /x/../peekaboot/...} spelling hides Peekaboot's own endpoints from the
 * prefix check. URLs written into a page get the context path put back in front via
 * {@link #basePath}.
 *
 * <p>One instance per application: the auto-configuration constructs it with the resolved
 * {@code management.endpoints.web.base-path}, so the actuator exclusion follows a
 * relocated management base path. {@link #defaults()} carries Spring Boot's default for
 * plain construction in tests.
 */
public final class PeekabootPaths {

    /** Peekaboot's UI/API prefix, relative to the context path; also the controllers' request mapping. */
    public static final String BASE_PATH = "/peekaboot";

    /** Spring Boot's default {@code management.endpoints.web.base-path}. */
    private static final String DEFAULT_MANAGEMENT_BASE_PATH = "/actuator";

    private static final String ERROR_PATH = "/error";

    /** Prefixes whose requests are neither traced nor given a toolbar. */
    private final Set<String> excludedPrefixes;

    /** @param managementBasePath the effective {@code management.endpoints.web.base-path} */
    public PeekabootPaths(String managementBasePath) {
        Set<String> prefixes = new LinkedHashSet<>(Set.of("/static/", "/webjars/", BASE_PATH + "/", ERROR_PATH + "/"));
        String managementPrefix = managementPrefix(managementBasePath);
        if (managementPrefix != null) {
            prefixes.add(managementPrefix);
        }
        this.excludedPrefixes = Set.copyOf(prefixes);
    }

    /** The exclusions at Spring Boot's default management base path - plain construction for tests. */
    public static PeekabootPaths defaults() {
        return new PeekabootPaths(DEFAULT_MANAGEMENT_BASE_PATH);
    }

    /**
     * {@code /manage} as the prefix {@code /manage/}, tolerating a missing leading or an
     * extra trailing slash. A base path of {@code /} - management endpoints at the
     * application root - has no prefix of its own to match by, so nothing extra is
     * excluded (null), rather than a {@code /} prefix that would exclude every request.
     */
    private static String managementPrefix(String managementBasePath) {
        String path = managementBasePath == null ? "" : managementBasePath.strip();
        path = path.startsWith("/") ? path : "/" + path;
        path = path.endsWith("/") ? path : path + "/";
        return "/".equals(path) ? null : path;
    }

    public Set<String> excludedPrefixes() {
        return excludedPrefixes;
    }

    public boolean isExcluded(String pathWithinApplication) {
        // exact match so /errors or /error-report are not excluded
        if (ERROR_PATH.equals(pathWithinApplication)) {
            return true;
        }
        for (String prefix : excludedPrefixes) {
            if (pathWithinApplication.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The same exclusions as Spring MVC path patterns, for registering the interceptor. */
    public String[] excludePatterns() {
        List<String> patterns = new ArrayList<>();
        for (String prefix : excludedPrefixes) {
            patterns.add(prefix + "**");
        }
        patterns.add(ERROR_PATH);
        return patterns.toArray(String[]::new);
    }

    /**
     * The request's path below the context path, as the container mapped it - decoded and
     * normalised, unlike {@link HttpServletRequest#getRequestURI()}.
     */
    public static String pathWithinApplication(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }

    /** {@link #BASE_PATH} as the browser has to address it: behind the request's context path. */
    public static String basePath(HttpServletRequest request) {
        return request.getContextPath() + BASE_PATH;
    }
}
