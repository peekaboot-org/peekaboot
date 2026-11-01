package org.peekaboot.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;

class RepeatedQueryIssueJsonTest {

    /** A repeated query is served with its own fields beside the shared ones, so an agent needs no join. */
    @Test
    void servesTheTypeSpecificFields() {
        List<TraceIssue> issues = List.of(new RepeatedQueryIssue(
                IssueType.REPEATED_QUERY,
                IssueSeverity.WARNING,
                "p",
                5,
                5,
                IssueUnit.QUERIES,
                "m",
                "select ?",
                5,
                12,
                List.of("a", "b", "c", "d", "e")));

        String json = PeekabootJson.MAPPER.writeValueAsString(issues);

        assertThat(json)
                .isEqualTo("[{\"type\":\"REPEATED_QUERY\",\"severity\":\"warning\",\"spanId\":\"p\","
                        + "\"value\":5,\"threshold\":5,\"unit\":\"queries\",\"message\":\"m\",\"statement\":\"select ?\","
                        + "\"count\":5,\"totalMs\":12,\"spanIds\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}]");
    }
}
