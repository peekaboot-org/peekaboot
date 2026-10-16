package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.resultSet;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowCountsTest {

    @Test
    void aResultSetBelongsToTheQueryRecordedBeforeIt() {
        Map<String, Long> rowCounts = RowCounts.byQuerySpanId(List.of(
                jdbcQuery("q1", "select 1").order(1).build(),
                resultSet("rs1", 3).order(2).build(),
                jdbcQuery("q2", "select 2").order(3).build(),
                resultSet("rs2", 5).order(4).build()));

        assertThat(rowCounts).containsExactlyInAnyOrderEntriesOf(Map.of("q1", 3L, "q2", 5L));
    }

    @Test
    void aResultSetRecordedBeforeAnyQueryBelongsToNone() {
        Map<String, Long> rowCounts = RowCounts.byQuerySpanId(List.of(
                resultSet("rs0", 9).order(1).build(),
                jdbcQuery("q1", "select 1").order(2).build()));

        assertThat(rowCounts).isEmpty();
    }

    /** The first result set after a query is its own; a later one before the next query is not, even when the first count does not parse. */
    @Test
    void onlyTheFirstResultSetAfterAQueryCounts() {
        Map<String, Long> rowCounts = RowCounts.byQuerySpanId(List.of(
                jdbcQuery("q1", "select 1").order(1).build(),
                resultSet("rs1", 3).order(2).build(),
                resultSet("rs2", 7).order(3).build(),
                jdbcQuery("q2", "select 2").order(4).build(),
                span("unparsable")
                        .named("result-set")
                        .tag("jdbc.row-count", "not-a-number")
                        .order(5)
                        .build(),
                resultSet("rs3", 11).order(6).build()));

        assertThat(rowCounts).containsExactlyInAnyOrderEntriesOf(Map.of("q1", 3L));
    }
}
