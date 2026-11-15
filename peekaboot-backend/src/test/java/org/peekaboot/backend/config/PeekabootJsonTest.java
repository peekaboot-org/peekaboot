package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.testsupport.SpanNodes;

class PeekabootJsonTest {

    /** JSON has no NaN and no infinity, so every value headed for the wire passes this one helper. */
    @Test
    void aValueJsonCannotWriteBecomesNull() {
        assertThat(PeekabootJson.finiteOrNull(Double.NaN)).isNull();
        assertThat(PeekabootJson.finiteOrNull(Double.POSITIVE_INFINITY)).isNull();
        assertThat(PeekabootJson.finiteOrNull(Double.NEGATIVE_INFINITY)).isNull();
        assertThat(PeekabootJson.finiteOrNull(null)).isNull();
        assertThat(PeekabootJson.finiteOrNull(1.5)).isEqualTo(1.5);
        assertThat(PeekabootJson.finiteOrNull(0.0)).isEqualTo(0.0);
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
