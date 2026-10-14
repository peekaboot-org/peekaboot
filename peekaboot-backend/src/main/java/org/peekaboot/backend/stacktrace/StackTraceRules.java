package org.peekaboot.backend.stacktrace;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;

/**
 * What every surface folds a stack trace with. One instance serves the error page and the Logs
 * tab, so the two cannot fold the same trace differently.
 *
 * @param exclusions the frames folding hides; empty where folding is off
 * @param applicationPackages the packages that mark a frame as the application's own; empty where none are registered
 */
public record StackTraceRules(List<String> exclusions, List<String> applicationPackages) {

    public StackTraceRules {
        exclusions = List.copyOf(exclusions);
        applicationPackages = List.copyOf(applicationPackages);
    }

    /**
     * Folding switched off empties the exclusion list rather than travelling as a flag, so every
     * consumer asks the same question and finds nothing to hide.
     *
     * @param explicit {@code peekaboot.stack-trace.exclude}
     * @param conversionWord {@code logging.exception-conversion-word}, null where unset
     */
    public static StackTraceRules resolve(
            boolean fold, List<String> explicit, @Nullable String conversionWord, List<String> applicationPackages) {
        return new StackTraceRules(
                fold ? ExclusionPatterns.resolve(explicit, conversionWord) : List.of(), applicationPackages);
    }

    public FoldedTrace fold(String trace) {
        return StackTraceFolding.fold(trace, exclusions, applicationPackages);
    }
}
