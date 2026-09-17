package org.peekaboot.backend.stacktrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The list an application already maintains for its log file is the list it wants here, so
 * `logging.exception-conversion-word` is read before the built-in default.
 */
class ExclusionPatternsTest {

    private static final String CONVERSION_WORD = "%wEx{full, java.lang.reflect.Method, org.apache.catalina, ByCGLIB}";

    @Test
    void anExplicitListWinsOverEverythingElse() {
        assertThat(ExclusionPatterns.resolve(List.of("com.acme"), CONVERSION_WORD))
                .containsExactly("com.acme");
    }

    @Test
    void readsTheConversionWordWhereNothingIsSetExplicitly() {
        assertThat(ExclusionPatterns.resolve(List.of(), CONVERSION_WORD))
                .containsExactly("java.lang.reflect.Method", "org.apache.catalina", "ByCGLIB");
    }

    /** Boot's own default carries no braces, so there is nothing to read and the default applies. */
    @Test
    void fallsBackToTheDefaultForABareConversionWord() {
        assertThat(ExclusionPatterns.resolve(List.of(), "%wEx")).isEqualTo(ExclusionPatterns.DEFAULT);
    }

    @Test
    void fallsBackToTheDefaultWhereNoConversionWordIsSet() {
        assertThat(ExclusionPatterns.resolve(List.of(), null)).isEqualTo(ExclusionPatterns.DEFAULT);
    }

    /** The first option is the depth Logback reads, not a package. */
    @Test
    void dropsTheDepthOption() {
        assertThat(ExclusionPatterns.resolve(List.of(), "%ex{short, org.springframework}"))
                .containsExactly("org.springframework");
        assertThat(ExclusionPatterns.resolve(List.of(), "%ex{12, org.springframework}"))
                .containsExactly("org.springframework");
    }

    @Test
    void ignoresWhitespaceAndNewlinesInTheConversionWord() {
        assertThat(ExclusionPatterns.resolve(List.of(), "%wEx{\n full, \n org.springframework, \n com.mysql \n}"))
                .containsExactly("org.springframework", "com.mysql");
    }

    /** A single-quoted YAML scalar keeps the line-continuation backslash a double-quoted one drops; folding leaves it before a space. */
    @Test
    void toleratesALiteralLineContinuationBackslash() {
        assertThat(ExclusionPatterns.resolve(List.of(), "%wEx{full, \\ org.springframework, \\ com.mysql}"))
                .containsExactly("org.springframework", "com.mysql");
    }

    /** Braces with nothing but a depth in them mean the application excluded nothing on purpose. */
    @Test
    void treatsADepthOnlyConversionWordAsAnEmptyList() {
        assertThat(ExclusionPatterns.resolve(List.of(), "%wEx{full}")).isEmpty();
    }
}
