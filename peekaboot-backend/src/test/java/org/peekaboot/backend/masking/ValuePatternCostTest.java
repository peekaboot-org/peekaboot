package org.peekaboot.backend.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every value pattern runs on the host's request thread over text a client chose, so its cost
 * must grow in step with its input. The test counts the characters the regex engine reads
 * instead of timing the match: a count is the same on every machine and under any load, and a
 * clock is neither.
 */
class ValuePatternCostTest {

    private static final int LENGTH = 5_000;

    /** Hostile runs: each is one unit repeated, with nothing that lets a pattern finish early. */
    private static final List<String> UNITS = List.of(
            "a",
            "aB3",
            "a.",
            "?",
            "?,",
            "-",
            ":",
            "/",
            "@",
            "=",
            "\"",
            "\\\"",
            "x://a:",
            "x://a:1,",
            "jdbc:oracle:thin:",
            "jdbc:oracle:thin:a/",
            "jdbc:db2:",
            "jdbc:db2:a",
            "jdbc:db2:a:",
            "jdbc:db2:a:a",
            "jdbc:db2://",
            "jdbc:db2://a/",
            "jdbc:db2://a/a:a=",
            " a=\"",
            " a=\"\\\"",
            "-----BEGIN ",
            " --a",
            "&a",
            ";a={",
            ";a={}}");

    @ParameterizedTest(name = "{0} on a run of [{1}]")
    @MethodSource("everyRuleOnEveryRun")
    void aRuleReadsInStepWithItsInput(String rule, String unit, Pattern pattern) {
        long once = reads(pattern, unit.repeat(LENGTH / unit.length()));
        long twice = reads(pattern, unit.repeat(2 * LENGTH / unit.length()));

        // Reading in step doubles the count; a rescan from every position quadruples it.
        assertThat(twice).isLessThan(3 * once + LENGTH);
    }

    static Stream<Arguments> everyRuleOnEveryRun() {
        return MaskingRules.VALUE_PATTERNS.stream()
                .flatMap(rule -> UNITS.stream().map(unit -> Arguments.of(rule.name(), unit, rule.pattern())));
    }

    /** How many characters {@code pattern} reads to find every match in {@code text}. */
    private static long reads(Pattern pattern, String text) {
        CountedText counted = new CountedText(text);
        Matcher matcher = pattern.matcher(counted);
        while (matcher.find()) {
            // every match is found, as MaskingEngine finds them
        }
        return counted.reads;
    }

    /** Counts {@code charAt} calls, the regex engine's unit of work. */
    private static final class CountedText implements CharSequence {

        private final String text;
        private long reads;

        private CountedText(String text) {
            this.text = text;
        }

        @Override
        public char charAt(int index) {
            reads++;
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
