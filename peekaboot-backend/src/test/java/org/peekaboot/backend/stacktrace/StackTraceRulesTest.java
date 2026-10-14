package org.peekaboot.backend.stacktrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;

class StackTraceRulesTest {

    private static final List<String> APP = List.of("com.example");

    private static final String TRACE = """
            java.lang.IllegalStateException: boom
            \tat com.example.Widget.process(Widget.java:10)
            \tat com.acme.vendor.Client.call(Client.java:42)""";

    /** Off empties the exclusion list rather than travelling as a flag, so every consumer asks the same question. */
    @Test
    void hidesNothingWithFoldingOff() {
        StackTraceRules rules = StackTraceRules.resolve(false, List.of("com.acme.vendor"), null, APP);

        assertThat(rules.exclusions()).isEmpty();
        assertThat(rules.applicationPackages()).isEqualTo(APP);
    }

    @Test
    void takesTheExplicitListFirst() {
        StackTraceRules rules =
                StackTraceRules.resolve(true, List.of("com.acme.vendor"), "%wEx{full, org.example}", APP);

        assertThat(rules.exclusions()).containsExactly("com.acme.vendor");
    }

    @Test
    void fallsBackToTheConversionWordAndThenTheBuiltInList() {
        assertThat(StackTraceRules.resolve(true, List.of(), "%wEx{full, org.example}", APP)
                        .exclusions())
                .containsExactly("org.example");
        assertThat(StackTraceRules.resolve(true, List.of(), null, APP).exclusions())
                .isEqualTo(ExclusionPatterns.DEFAULT);
    }

    @Test
    void foldsWithItsOwnExclusionsAndPackages() {
        FoldedTrace folded = new StackTraceRules(List.of("com.acme.vendor"), APP).fold(TRACE);

        assertThat(folded.applicationFrames()).containsExactly(new Range(1, 2));
        assertThat(folded.hidden()).containsExactly(new Range(2, 3));
    }
}
