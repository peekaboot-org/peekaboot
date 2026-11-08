package org.peekaboot.testingapp.integration;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The running application's own mappings under Peekaboot's API, so a test that walks them holds an endpoint added later to its rule. */
final class ApiMappings {

    private static final String API = "/peekaboot/api";

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{([^}]+)}");

    private ApiMappings() {}

    /**
     * Every path pattern mapped under {@code /peekaboot/api}, the index included. The event stream
     * goes last, since a request it does not refuse would wait on a stream that never ends.
     */
    static List<String> paths(RequestMappingHandlerMapping mappings) {
        return infos(mappings)
                .flatMap(info -> info.getPatternValues().stream())
                .filter(ApiMappings::isApiPath)
                .distinct()
                .sorted(Comparator.comparing((String pattern) -> pattern.endsWith("/stream"))
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** The methods each API mapping answers. */
    static Stream<Set<RequestMethod>> methods(RequestMappingHandlerMapping mappings) {
        return infos(mappings)
                .filter(info -> info.getPatternValues().stream().anyMatch(ApiMappings::isApiPath))
                .map(info -> info.getMethodsCondition().getMethods());
    }

    /** {@code pattern} as a request that sends one parameter no endpoint takes, each path variable filled in. */
    static String withUnknownParameter(String pattern) {
        return TEMPLATE_VARIABLE.matcher(pattern).replaceAll("x") + "?peekabootTypo=1";
    }

    /** The names of {@code pattern}'s path variables, in order. */
    static List<String> templateVariables(String pattern) {
        return TEMPLATE_VARIABLE
                .matcher(pattern)
                .results()
                .map(match -> match.group(1))
                .toList();
    }

    private static Stream<RequestMappingInfo> infos(RequestMappingHandlerMapping mappings) {
        return mappings.getHandlerMethods().keySet().stream();
    }

    private static boolean isApiPath(String pattern) {
        return pattern.equals(API) || pattern.startsWith(API + "/");
    }
}
