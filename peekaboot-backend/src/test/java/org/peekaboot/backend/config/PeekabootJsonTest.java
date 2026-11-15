package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.testsupport.SpanNodes;

class PeekabootJsonTest {

    /** JSON has no NaN, so every value headed for the wire maps NaN to null through this one helper. */
    @Test
    void nanBecomesNullAndEverythingElsePassesThrough() {
        assertThat(PeekabootJson.nanToNull(Double.NaN)).isNull();
        assertThat(PeekabootJson.nanToNull(null)).isNull();
        assertThat(PeekabootJson.nanToNull(1.5)).isEqualTo(1.5);
        assertThat(PeekabootJson.nanToNull(0.0)).isEqualTo(0.0);
    }

    /** Each span nests two levels, itself and its children, so 300 of them pass Jackson's default of 500. */
    @Test
    void writesASpanChainDeeperThanJacksonsDefaultLimit() {
        SpanNode root = SpanNodes.node("span299").build();
        for (int i = 298; i >= 0; i--) {
            root = SpanNodes.node("span" + i).children(List.of(root)).build();
        }
        SpanNode chain = root;

        assertThatCode(() -> PeekabootJson.MAPPER.writeValueAsString(chain)).doesNotThrowAnyException();
    }
}
