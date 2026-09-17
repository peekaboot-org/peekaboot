package org.peekaboot.backend.stacktrace;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The frames a stack trace hides, resolved once from the first source that has an opinion: the
 * application's own {@code peekaboot.stack-trace.exclude}, then the exclusions it already keeps
 * for its log file in {@code logging.exception-conversion-word}, then a built-in list.
 */
public final class ExclusionPatterns {

    /**
     * Reflection, the container, the web framework, the template engine and the drivers and
     * proxies a request passes through on its way to the application's own code. An application
     * whose stack differs says so through {@code logging.exception-conversion-word}, which is read
     * ahead of this, or through the property.
     */
    public static final List<String> DEFAULT = List.of(
            "java.lang.reflect.Method",
            "jdk.internal.reflect",
            "sun.reflect",
            "org.apache.catalina",
            "org.apache.coyote",
            "org.apache.tomcat",
            "org.springframework",
            "org.thymeleaf",
            "org.attoparser",
            "jakarta.servlet",
            "net.sf.cglib",
            "ByCGLIB",
            "org.zalando.logbook",
            "net.ttddyy.dsproxy",
            "com.mysql");

    private ExclusionPatterns() {}

    public static List<String> resolve(List<String> explicit, String conversionWord) {
        if (explicit != null && !explicit.isEmpty()) {
            return List.copyOf(explicit);
        }
        return parseConversionWord(conversionWord).orElse(DEFAULT);
    }

    /**
     * The options inside {@code %wEx{...}} less the leading depth. Empty where the word carries no
     * braces at all, which is Boot's own default and means "no opinion" rather than "exclude
     * nothing" (that case is an empty list wrapped in a present {@link Optional}). Logback would
     * read an option naming a registered evaluator as an evaluator rather than a pattern; without
     * its evaluator map the two are indistinguishable here, and a pattern that matches no frame
     * costs nothing.
     */
    static Optional<List<String>> parseConversionWord(String conversionWord) {
        if (conversionWord == null) {
            return Optional.empty();
        }
        int open = conversionWord.indexOf('{');
        int close = conversionWord.lastIndexOf('}');
        if (open < 0 || close < open) {
            return Optional.empty();
        }
        return Optional.of(
                Arrays.stream(conversionWord.substring(open + 1, close).split(","))
                        .map(option -> option.replace("\\", " ").trim())
                        .filter(option -> !option.isEmpty())
                        .skip(1)
                        .toList());
    }
}
