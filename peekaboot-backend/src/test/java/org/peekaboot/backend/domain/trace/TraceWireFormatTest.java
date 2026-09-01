package org.peekaboot.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SpanNodes.node;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;

/** The two enums replaced string literals the frontend already reads; the JSON must not have moved. */
class TraceWireFormatTest {

    @Test
    void spanStatusSerialisesAsItsUpperCaseName() {
        String json = PeekabootJson.MAPPER.writeValueAsString(
                node("s1").status(SpanStatus.ERROR).build());

        assertThat(json).contains("\"status\":\"ERROR\"");
    }

    @Test
    void issueSeveritySerialisesAsTheLowerCaseWord() {
        String json = PeekabootJson.MAPPER.writeValueAsString(List.of(
                new SpanIssue(IssueType.SLOW, "slow", IssueSeverity.WARNING),
                new SpanIssue(IssueType.ERROR, "failed", IssueSeverity.ERROR)));

        assertThat(json).contains("\"severity\":\"warning\"").contains("\"severity\":\"error\"");
    }
}
