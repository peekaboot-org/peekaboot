package org.peekaboot.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;

class SpanIssueJsonTest {

    /** An agent reads the unit as a word, the way the severity already reads. */
    @Test
    void servesEveryFieldWithTheUnitAsAWord() {
        String json = PeekabootJson.MAPPER.writeValueAsString(new SpanIssue(
                IssueType.SLOW, IssueSeverity.WARNING, "s1", 120, 100, IssueUnit.MS, "This span spent 120 ms."));

        assertThat(json)
                .isEqualTo("{\"type\":\"SLOW\",\"severity\":\"warning\",\"spanId\":\"s1\",\"value\":120,"
                        + "\"threshold\":100,\"unit\":\"ms\",\"message\":\"This span spent 120 ms.\"}");
    }
}
