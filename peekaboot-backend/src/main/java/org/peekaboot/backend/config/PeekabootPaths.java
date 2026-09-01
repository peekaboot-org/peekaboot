package org.peekaboot.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Peekaboot's own URL space: the prefix its UI and API live under, and the prefixes its
 * filters and interceptor leave alone.
 *
 * <p>Everything here is relative to the servlet context. Requests are matched on their
 * {@linkplain #pathWithinApplication path within the application}, which the container
 * has already decoded and normalised, so neither a {@code server.servlet.context-path}
 * nor a {@code /x/../peekaboot/...} spelling hides Peekaboot's own endpoints from the
 * prefix check. URLs written into a page get the context path put back in front via
 * {@link #basePath}.
 */
public final class PeekabootPaths {

    /** Peekaboot's UI/API prefix, relative to the context path; also the controllers' request mapping. */
    public static final String BASE_PATH = "/peekaboot";

    /** Prefixes whose requests are neither traced nor given a toolbar. */
    public static final Set<String> EXCLUDED_PREFIXES =
            Set.of("/static/", "/webjars/", "/actuator/", BASE_PATH + "/", "/error/");

    private static final String ERROR_PATH = "/error";

    private PeekabootPaths() {}

    public static boolean isExcluded(String pathWithinApplication) {
        // exact match so /errors or /error-report are not excluded
        if (ERROR_PATH.equals(pathWithinApplication)) {
            return true;
        }
        for (String prefix : EXCLUDED_PREFIXES) {
            if (pathWithinApplication.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** The same exclusions as Spring MVC path patterns, for registering the interceptor. */
    public static String[] excludePatterns() {
        List<String> patterns = new ArrayList<>();
        for (String prefix : EXCLUDED_PREFIXES) {
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
