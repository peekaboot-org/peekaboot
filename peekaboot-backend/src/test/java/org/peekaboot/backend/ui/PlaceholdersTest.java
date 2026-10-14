package org.peekaboot.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    @Test
    void fillsEveryOccurrence() {
        assertThat(Placeholders.fill("{{A}}-{{A}}", Map.of("A", "x"))).isEqualTo("x-x");
    }

    /** A value is never scanned again, so request text that spells a placeholder lands verbatim. */
    @Test
    void neverReadsAValueAsAPlaceholder() {
        assertThat(Placeholders.fill("{{A}}|{{B}}", Map.of("A", "{{B}}", "B", "b")))
                .isEqualTo("{{B}}|b");
    }

    @Test
    void leavesANameItHasNoValueForAsWritten() {
        assertThat(Placeholders.fill("{{A}} {{Z}}", Map.of("A", "a"))).isEqualTo("a {{Z}}");
    }

    /** Exception messages carry dollar signs and backslashes; neither is a group reference here. */
    @Test
    void insertsRegexReplacementCharactersVerbatim() {
        assertThat(Placeholders.fill("{{A}}", Map.of("A", "costs $1 \\ more"))).isEqualTo("costs $1 \\ more");
    }

    @Test
    void leavesBracesThatNameNothingAlone() {
        assertThat(Placeholders.fill("{ {A} } {{a}} {{}}", Map.of("A", "x", "a", "y")))
                .isEqualTo("{ {A} } {{a}} {{}}");
    }
}
