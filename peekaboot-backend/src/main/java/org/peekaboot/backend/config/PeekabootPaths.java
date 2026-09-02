package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Peekaboot's own URL space: the prefix its UI and API live under, and the prefixes its
 * filters, interceptor and span exporter leave alone.
 *
 * <p>The exclusions are relative to the servlet context. Requests are matched on their
 * {@linkplain #pathWithinApplication path within the application}, which the container
 * has already decoded and normalised, so neither a {@code server.servlet.context-path}
 * nor a {@code /x/../peekaboot/...} spelling hides Peekaboot's own endpoints from the
 * prefix check. A path that still carries the context path - a span's HTTP path tag -
 * goes through {@link #isExcludedRequestPath}, which strips it first. URLs written into
 * a page get the context path put back in front via {@link #basePath}.
 *
 * <p>One instance per application: the auto-configuration constructs it with the resolved
 * {@code management.endpoints.web.base-path}, so the actuator exclusion follows a
 * relocated management base path, and with the resolved {@code server.servlet.context-path}
 * for the stripping above.
 */
public final class PeekabootPaths {

    /** Peekaboot's UI/API prefix, relative to the context path; also the controllers' request mapping. */
    public static final String BASE_PATH = "/peekaboot";

    /**
     * Where the frontend jar ships the UI. Outside every default static location, so a
     * consumer with Peekaboot off serves none of it.
     */
    public static final String CLASSPATH_ROOT = "/META-INF" + BASE_PATH;

    /** Spring Boot's default {@code management.endpoints.web.base-path}. */
    private static final String DEFAULT_MANAGEMENT_BASE_PATH = "/actuator";

    /** Prefixes whose requests are neither traced nor given a toolbar. */
    private final Set<String> excludedPrefixes;

    /** The effective servlet context path, {@code ""} at the root; see {@link #isExcludedRequestPath}. */
    private final String contextPath;

    /**
     * @param managementBasePath the effective {@code management.endpoints.web.base-path}
     * @param contextPath the effective {@code server.servlet.context-path}; empty or {@code /} at the root
     */
    public PeekabootPaths(String managementBasePath, String contextPath) {
        Set<String> prefixes = new LinkedHashSet<>(Set.of("/static/", "/webjars/", BASE_PATH + "/", "/error/"));
        String managementPrefix = managementPrefix(managementBasePath);
        if (managementPrefix != null) {
            prefixes.add(managementPrefix);
        }
        this.excludedPrefixes = Set.copyOf(prefixes);
        this.contextPath = normalisedContextPath(contextPath);
    }

    /** The exclusions at Spring Boot's defaults - {@code /actuator}, root context path - for plain construction in tests. */
    public static PeekabootPaths defaults() {
        return new PeekabootPaths(DEFAULT_MANAGEMENT_BASE_PATH, "");
    }

    /** {@code /app} as a strippable prefix, tolerating the trailing-slash and bare-root spellings a property can carry. */
    private static String normalisedContextPath(String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip();
        if (path.isEmpty() || "/".equals(path)) {
            return "";
        }
        path = path.startsWith("/") ? path : "/" + path;
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
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

    /** True for the prefix itself ({@code /peekaboot}) and everything below it; {@code /errors} stays an application path. */
    public boolean isExcluded(String pathWithinApplication) {
        for (String prefix : excludedPrefixes) {
            if (pathWithinApplication.startsWith(prefix) || prefix.equals(pathWithinApplication + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link #isExcluded}, for a server-absolute path that still carries the servlet context
     * path - the shape an HTTP span's path tag has. The context path is stripped before
     * matching, so the exclusions hold the same with and without a
     * {@code server.servlet.context-path}.
     */
    public boolean isExcludedRequestPath(String path) {
        if (!contextPath.isEmpty() && path.startsWith(contextPath + "/")) {
            return isExcluded(path.substring(contextPath.length()));
        }
        return isExcluded(path);
    }

    /** The same exclusions as Spring MVC path patterns, for registering the interceptor; {@code /x/**} matches {@code /x} too. */
    public String[] excludePatterns() {
        return excludedPrefixes.stream().map(prefix -> prefix + "**").toArray(String[]::new);
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
