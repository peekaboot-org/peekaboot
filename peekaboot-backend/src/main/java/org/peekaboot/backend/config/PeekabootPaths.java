package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Peekaboot's own URL space: the prefix its UI and API live under, and the prefixes its
 * filters, interceptor and span exporter leave alone.
 *
 * <p>The exclusions are relative to the DispatcherServlet, which serves every one of them, so
 * they are also the path patterns Spring MVC matches. Requests are matched on their
 * {@linkplain #pathWithinApplication path within the application}, which the container has
 * already decoded and normalised. Neither a {@code server.servlet.context-path} nor a
 * {@code /x/../peekaboot/...} spelling hides Peekaboot's own endpoints from the prefix check.
 * The DispatcherServlet's own prefix, set by {@code spring.mvc.servlet.path}, is stripped first.
 * A span's HTTP path tag still carries the context path, so it goes through
 * {@link #isExcludedRequestPath}, which strips that too. URLs written into a page get both put
 * back in front via {@link #basePath}.
 *
 * <p>One instance per application. The auto-configuration constructs it with the resolved
 * {@code management.endpoints.web.base-path}, so the actuator exclusion follows a relocated
 * management base path. It also passes the resolved {@code server.servlet.context-path} and the
 * DispatcherServlet's prefix.
 */
public final class PeekabootPaths {

    /** Peekaboot's UI/API prefix, relative to the context path; every controller's request mapping starts with it. */
    public static final String BASE_PATH = "/peekaboot";

    /**
     * Where the frontend jar ships the UI. Outside every default static location, so a
     * consumer with Peekaboot off serves none of it.
     */
    public static final String CLASSPATH_ROOT = "/META-INF" + BASE_PATH;

    /** Spring Boot's default {@code management.endpoints.web.base-path}. */
    private static final String DEFAULT_MANAGEMENT_BASE_PATH = "/actuator";

    /** springdoc's own default for {@code springdoc.swagger-ui.path}, for the toolbar's idle mode and the lifecycle banner. */
    public static final String DEFAULT_SWAGGER_UI_PATH = "/swagger-ui.html";

    /** Prefixes whose requests are neither traced nor given a toolbar. */
    private final Set<String> excludedPrefixes;

    /** The effective servlet context path, {@code ""} at the root; see {@link #isExcludedRequestPath}. */
    private final String contextPath;

    /** Where the DispatcherServlet is mapped below the context path, {@code ""} at the root. */
    private final String dispatcherServletPrefix;

    /**
     * @param managementBasePath the effective {@code management.endpoints.web.base-path}
     * @param contextPath the effective {@code server.servlet.context-path}; empty or {@code /} at the root
     * @param dispatcherServletPrefix the DispatcherServlet's mapping without its trailing {@code /*}, as
     *     Boot's {@code DispatcherServletPath.getPrefix()} gives it; {@code ""} at the root
     */
    public PeekabootPaths(String managementBasePath, String contextPath, String dispatcherServletPrefix) {
        Set<String> prefixes = new LinkedHashSet<>(Set.of("/static/", "/webjars/", BASE_PATH + "/", "/error/"));
        String managementPrefix = managementPrefix(managementBasePath);
        if (managementPrefix != null) {
            prefixes.add(managementPrefix);
        }
        this.excludedPrefixes = Set.copyOf(prefixes);
        this.contextPath = normaliseContextPath(contextPath);
        this.dispatcherServletPrefix = dispatcherServletPrefix;
    }

    /** The exclusions at Spring Boot's defaults, {@code /actuator} and everything at the root, for plain construction in tests. */
    public static PeekabootPaths defaults() {
        return new PeekabootPaths(DEFAULT_MANAGEMENT_BASE_PATH, "", "");
    }

    /**
     * {@code /app} as a strippable prefix, tolerating the trailing-slash and bare-root
     * spellings a {@code server.servlet.context-path} property can carry. Static, so the
     * lifecycle banner can use it in an application that has no {@code PeekabootPaths} bean.
     */
    public static String normaliseContextPath(String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip();
        if (path.isEmpty() || "/".equals(path)) {
            return "";
        }
        path = path.startsWith("/") ? path : "/" + path;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * {@code /manage} as the prefix {@code /manage/}, tolerating a missing leading or an
     * extra trailing slash. A base path of {@code /} puts the management endpoints at the
     * application root. It has no prefix of its own to match by, so nothing extra is excluded
     * (null). A {@code /} prefix would exclude every request.
     */
    private static String managementPrefix(String managementBasePath) {
        String path = managementBasePath == null ? "" : managementBasePath.strip();
        path = path.startsWith("/") ? path : "/" + path;
        path = path.endsWith("/") ? path : path + "/";
        return "/".equals(path) ? null : path;
    }

    /** True for the prefix itself ({@code /peekaboot}) and everything below it; {@code /errors} stays an application path. */
    public boolean isExcluded(String pathWithinApplication) {
        String path = pathWithinDispatcherServlet(pathWithinApplication);
        if (path == null) {
            return false;
        }
        for (String prefix : excludedPrefixes) {
            if (path.startsWith(prefix) || prefix.equals(path + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The path as the DispatcherServlet's own mappings see it, or null for a path outside its
     * prefix, which none of Peekaboot's endpoints can be.
     */
    public @Nullable String pathWithinDispatcherServlet(String pathWithinApplication) {
        if (dispatcherServletPrefix.isEmpty()) {
            return pathWithinApplication;
        }
        if (pathWithinApplication.equals(dispatcherServletPrefix)) {
            return "/";
        }
        if (pathWithinApplication.startsWith(dispatcherServletPrefix + "/")) {
            return pathWithinApplication.substring(dispatcherServletPrefix.length());
        }
        return null;
    }

    /**
     * {@link #isExcluded}, for a server-absolute path that still carries the servlet context
     * path, as an HTTP span's path tag does. The context path is stripped before
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
     * The request's path below the context path, as the container mapped it. Unlike
     * {@link HttpServletRequest#getRequestURI()}, it is decoded and normalised.
     */
    public static String pathWithinApplication(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }

    /** {@link #BASE_PATH} below the context path: behind the DispatcherServlet's prefix, where its controllers sit. */
    public String dashboardPath() {
        return belowDispatcherServlet(BASE_PATH);
    }

    /** A path the DispatcherServlet maps, as a path below the context path. */
    public String belowDispatcherServlet(String pathWithinDispatcherServlet) {
        return dispatcherServletPrefix + pathWithinDispatcherServlet;
    }

    /** {@link #BASE_PATH} as the browser has to address it: behind the request's context path and the DispatcherServlet's prefix. */
    public String basePath(HttpServletRequest request) {
        return request.getContextPath() + dashboardPath();
    }
}
