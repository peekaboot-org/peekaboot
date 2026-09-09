package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpanDuplicateMatcherTest {

    @Test
    void theDoubleInstrumentedTwinOfAQueryIsItsDuplicate() {
        SpanData real = jdbcQuery("real", "SELECT 1").build();
        SpanData twin = jdbcDuplicate("twin", "real", "SELECT 1").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(real, twin)).isTrue();
    }

    /** Either decorator may carry the datasource name under its own key; neither key takes part in the comparison. */
    @Test
    void theTwoServiceIdentifierKeysAreIgnoredWhicheverEachSpanSets() {
        SpanData byPeerService =
                span("a").named("query").tag("peer.service", "orders_db").build();
        SpanData byDatasourceName = span("b")
                .named("query")
                .tag("jdbc.datasource.name", "dataSource")
                .build();

        assertThat(SpanDuplicateMatcher.isDuplicate(byPeerService, byDatasourceName))
                .isTrue();
    }

    /** Two unrelated spans that happen to share a name and tags fold too; the bundle's parent check is what keeps them apart. */
    @Test
    void spansWithTheSameNameAndTagsAndNoServiceKeyAreDuplicates() {
        SpanData a =
                span("a").named("connection").tag("db.system", "postgresql").build();
        SpanData b =
                span("b").named("connection").tag("db.system", "postgresql").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(a, b)).isTrue();
    }

    @Test
    void aSpanWithoutTagsMatchesOneWithNone() {
        // the record allows a null tag map; the builder never produces one
        SpanData untagged = new SpanData(
                "t",
                "a",
                null,
                "connection",
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                Duration.ZERO,
                null,
                List.of(),
                null,
                null,
                null,
                1);
        SpanData empty = span("b").named("connection").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(untagged, empty)).isTrue();
        assertThat(SpanDuplicateMatcher.isDuplicate(empty, untagged)).isTrue();
    }

    @Test
    void spansWithDifferentNamesAreNotDuplicates() {
        SpanData query = jdbcQuery("a", "SELECT 1").build();
        SpanData resultSet = jdbcQuery("b", "SELECT 1").named("result-set").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(query, resultSet)).isFalse();
    }

    @Test
    void spansWhoseOtherTagsDifferAreNotDuplicates() {
        SpanData first = jdbcQuery("a", "SELECT 1").build();
        SpanData second = jdbcDuplicate("b", "a", "SELECT 2").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(first, second)).isFalse();
    }
}
