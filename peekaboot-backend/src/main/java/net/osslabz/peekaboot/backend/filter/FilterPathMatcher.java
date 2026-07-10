package net.osslabz.peekaboot.backend.filter;

import java.util.Set;

public final class FilterPathMatcher {

    public static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/static/",
            "/webjars/",
            "/actuator/",
            "/peekaboot/",
            "/error/"
    );

    private FilterPathMatcher() {}

    public static boolean shouldSkip(String path) {
        // exact match so /errors or /error-report are not excluded
        if ("/error".equals(path)) {
            return true;
        }
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
