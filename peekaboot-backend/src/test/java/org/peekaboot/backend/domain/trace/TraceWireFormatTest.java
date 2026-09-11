package org.peekaboot.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SpanNodes.node;

import io.micrometer.tracing.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;

/** The enums and the typed tag map replaced strings the frontend already reads; the JSON must not have moved. */
class TraceWireFormatTest {

    @Test
    void spanStatusSerialisesAsItsUpperCaseName() {
        String json = PeekabootJson.MAPPER.writeValueAsString(
                node("s1").status(SpanStatus.ERROR).build());

        assertThat(json).contains("\"status\":\"ERROR\"");
    }

    @Test
    void spanKindSerialisesAsItsNameAndTagsAsAPlainObject() {
        String json = PeekabootJson.MAPPER.writeValueAsString(node("s1")
                .kind(Span.Kind.CLIENT)
                .tags(Map.of("db.system", "postgresql"))
                .build());

        assertThat(json).contains("\"kind\":\"CLIENT\"").contains("\"tags\":{\"db.system\":\"postgresql\"}");
    }

    @Test
    void issueSeveritySerialisesAsTheLowerCaseWord() {
        String json = PeekabootJson.MAPPER.writeValueAsString(List.of(
                new SpanIssue(IssueType.SLOW, "slow", IssueSeverity.WARNING),
                new SpanIssue(IssueType.ERROR, "failed", IssueSeverity.ERROR)));

        assertThat(json).contains("\"severity\":\"warning\"").contains("\"severity\":\"error\"");
    }

    @Test
    void rowCountSerialisesAsANumber() {
        String json =
                PeekabootJson.MAPPER.writeValueAsString(node("s1").rowCount(3L).build());

        assertThat(json).contains("\"rowCount\":3");
    }
}
