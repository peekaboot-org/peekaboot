package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.SpanData;

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
        SpanData byPeerService = span("a")
                .named("query")
                .tag("db.system", "postgresql")
                .tag("peer.service", "orders_db")
                .build();
        SpanData byDatasourceName = span("b")
                .named("query")
                .tag("db.system", "postgresql")
                .tag("jdbc.datasource.name", "dataSource")
                .build();

        assertThat(SpanDuplicateMatcher.isDuplicate(byPeerService, byDatasourceName))
                .isTrue();
    }

    /** Two unrelated datasource spans that happen to share a name and tags fold too; the bundle's parent check is what keeps them apart. */
    @Test
    void spansWithTheSameNameAndTagsAndNoServiceKeyAreDuplicates() {
        SpanData a =
                span("a").named("connection").tag("db.system", "postgresql").build();
        SpanData b =
                span("b").named("connection").tag("db.system", "postgresql").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(a, b)).isTrue();
    }

    @Test
    void spansWithoutTagsAreNotDuplicates() {
        // the record allows a null tag map; the builder never produces one
        SpanData untagged = new SpanData(
                "t", "a", null, false, "connection", null, Instant.EPOCH, Instant.EPOCH, null, List.of(), null, 1);
        SpanData empty = span("b").named("connection").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(untagged, empty)).isFalse();
        assertThat(SpanDuplicateMatcher.isDuplicate(empty, untagged)).isFalse();
    }

    /** A method that calls itself under {@code @Observed} nests two spans alike in name and tags. */
    @Test
    void aParentAndChildThatAreNoDatasourceSpansAreNotDuplicates() {
        SpanData outer = span("outer")
                .named("OrderService.process")
                .tag("class", "OrderService")
                .tag("method", "process")
                .build();
        SpanData inner = span("inner")
                .parent("outer")
                .named("OrderService.process")
                .tag("class", "OrderService")
                .tag("method", "process")
                .build();

        assertThat(SpanDuplicateMatcher.isDuplicate(inner, outer)).isFalse();
    }

    /** One datasource span does not make a pair: the other has to be one too, or the relation stops being transitive. */
    @Test
    void aDatasourceSpanIsNoDuplicateOfAPlainSpanOfTheSameName() {
        SpanData datasource = span("a")
                .named("connection")
                .tag("jdbc.datasource.name", "dataSource")
                .build();
        SpanData plain = span("b").named("connection").build();

        assertThat(SpanDuplicateMatcher.isDuplicate(datasource, plain)).isFalse();
        assertThat(SpanDuplicateMatcher.isDuplicate(plain, datasource)).isFalse();
    }

    /** The datasource name can be the only jdbc key a connection span carries, and it is a key the comparison skips. */
    @Test
    void twinsWhoseOnlyDatasourceKeyIsTheDatasourceNameAreDuplicates() {
        SpanData real = span("a")
                .named("connection")
                .tag("jdbc.datasource.name", "ordersDataSource")
                .build();
        SpanData twin = span("b")
                .parent("a")
                .named("connection")
                .tag("jdbc.datasource.name", "dataSource")
                .build();

        assertThat(SpanDuplicateMatcher.isDuplicate(twin, real)).isTrue();
    }

    @Test
    void aFailedSpanIsNoDuplicateOfOneThatSucceeded() {
        SpanData succeeded = jdbcQuery("real", "SELECT 1").build();
        SpanData failed = jdbcDuplicate("twin", "real", "SELECT 1")
                .exception("java.sql.SQLException", "boom")
                .build();

        assertThat(SpanDuplicateMatcher.isDuplicate(failed, succeeded)).isFalse();
        assertThat(SpanDuplicateMatcher.isDuplicate(succeeded, failed)).isFalse();
    }

    @Test
    void twoTwinsThatBothFailedAreDuplicates() {
        SpanData real = jdbcQuery("real", "SELECT 1")
                .exception("java.sql.SQLException", "boom")
                .build();
        SpanData twin = jdbcDuplicate("twin", "real", "SELECT 1")
                .exception("java.sql.SQLException", "boom")
                .build();

        assertThat(SpanDuplicateMatcher.isDuplicate(twin, real)).isTrue();
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
