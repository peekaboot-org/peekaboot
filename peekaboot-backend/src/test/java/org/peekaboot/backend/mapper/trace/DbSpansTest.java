package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SpanNodes.node;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DbSpansTest {

    @Test
    void aClientSpanWithAnOpenTelemetryDbTagIsAQuery() {
        assertThat(DbSpans.isQuery(clientSpan(Map.of("db.system", "postgresql"))))
                .isTrue();
        assertThat(DbSpans.isQuery(clientSpan(Map.of("db.query.text", "SELECT 1"))))
                .isTrue();
    }

    @Test
    void aClientSpanWithADatasourceProxyQueryTagIsAQuery() {
        assertThat(DbSpans.isQuery(clientSpan(Map.of("jdbc.query[0]", "SELECT 1"))))
                .isTrue();
    }

    @Test
    void datasourceProxyConnectionAndResultSetSpansAreNotQueries() {
        assertThat(DbSpans.isQuery(clientSpan(Map.of("jdbc.datasource.name", "primary"))))
                .isFalse();
        assertThat(DbSpans.isQuery(clientSpan(Map.of("jdbc.row-count", "10")))).isFalse();
    }

    @Test
    void onlyTheClientSideOfAQueryCounts() {
        assertThat(DbSpans.isQuery(span("s1")
                        .kind(Span.Kind.SERVER)
                        .tags(Map.of("db.statement", "SELECT 1"))
                        .build()))
                .isFalse();
        assertThat(DbSpans.isQuery(
                        span("s1").tags(Map.of("db.statement", "SELECT 1")).build()))
                .isFalse();
    }

    @Test
    void aSpanWithoutTagsIsNotAQuery() {
        assertThat(DbSpans.isQuery(clientSpan(Map.of()))).isFalse();
    }

    @Test
    void theMappedTwinAppliesTheSameRule() {
        assertThat(DbSpans.isQuery(node("s1")
                        .kind(Span.Kind.CLIENT)
                        .tags(Map.of("db.system", "postgresql"))
                        .build()))
                .isTrue();
        assertThat(DbSpans.isQuery(node("s1")
                        .kind(Span.Kind.CLIENT)
                        .tags(Map.of("jdbc.row-count", "10"))
                        .build()))
                .isFalse();
        assertThat(DbSpans.isQuery(node("s1")
                        .kind(Span.Kind.SERVER)
                        .tags(Map.of("db.system", "postgresql"))
                        .build()))
                .isFalse();
    }

    @Test
    void sqlPrefersTheCurrentOpenTelemetryTagOverTheSupersededOneAndTheName() {
        assertThat(DbSpans.sql(span("s1")
                        .named("SELECT legacy")
                        .tags(Map.of("db.query.text", "SELECT current", "db.statement", "SELECT superseded"))
                        .build()))
                .isEqualTo("SELECT current");
        assertThat(DbSpans.sql(span("s1")
                        .named("SELECT name")
                        .tags(Map.of("db.statement", "SELECT superseded"))
                        .build()))
                .isEqualTo("SELECT superseded");
    }

    @Test
    void sqlReadsTheDatasourceProxyQueryTag() {
        assertThat(DbSpans.sql(
                        span("s1").tags(Map.of("jdbc.query[0]", "INSERT 1")).build()))
                .isEqualTo("INSERT 1");
    }

    @Test
    void sqlJoinsTheStatementsOfABatchInIndexOrder() {
        assertThat(DbSpans.sql(span("s1")
                        .tags(Map.of("jdbc.query[1]", "INSERT 1", "jdbc.query[0]", "INSERT 0"))
                        .build()))
                .isEqualTo("INSERT 0;\nINSERT 1");
    }

    @Test
    void sqlFallsBackToASqlShapedSpanNameAndIsNullOtherwise() {
        assertThat(DbSpans.sql(span("s1")
                        .named("UPDATE users SET active = true")
                        .tags(Map.of("db.system", "postgresql"))
                        .build()))
                .isEqualTo("UPDATE users SET active = true");
        assertThat(DbSpans.sql(span("s1")
                        .named("SELECT users")
                        .tags(Map.of("db.system", "postgresql"))
                        .build()))
                .isEqualTo("SELECT users");
        assertThat(DbSpans.sql(span("s1")
                        .named("query")
                        .tags(Map.of("db.system", "postgresql"))
                        .build()))
                .isNull();
    }

    /** The same priority as {@link DbSpans#sql}: the current OpenTelemetry name, its superseded spelling, then datasource-proxy's. */
    @Test
    void systemPrefersTheCurrentOpenTelemetryTagThenTheSupersededOneThenDatasourceProxyNames() {
        assertThat(DbSpans.system(Map.of("db.system.name", "postgresql", "db.system", "other")))
                .isEqualTo("postgresql");
        assertThat(DbSpans.system(Map.of("db.system", "mysql", "jdbc.datasource.name", "primary")))
                .isEqualTo("mysql");
        assertThat(DbSpans.system(Map.of("jdbc.datasource.name", "primary", "peer.service", "orders_db")))
                .isEqualTo("primary");
        assertThat(DbSpans.system(Map.of("peer.service", "orders_db"))).isEqualTo("orders_db");
        assertThat(DbSpans.system(Map.of("db.query.text", "SELECT 1"))).isNull();
    }

    private static org.peekaboot.backend.tracing.store.SpanData clientSpan(Map<String, String> tags) {
        return span("s1").kind(Span.Kind.CLIENT).tags(tags).build();
    }

    /** After mapping, the statement tags are gone: the query field is what says "query" then. */
    @Test
    void theMappedTwinTrustsTheQueryField() {
        assertThat(DbSpans.isQuery(node("s1")
                        .kind(Span.Kind.CLIENT)
                        .tags(Map.of("jdbc.datasource.name", "primary"))
                        .query("SELECT 1")
                        .build()))
                .isTrue();
    }

    @Test
    void rowCountIsTheParsedTagOfAResultSetSpan() {
        assertThat(DbSpans.rowCount(span("s1")
                        .named("result-set")
                        .tags(Map.of("jdbc.row-count", "10"))
                        .build()))
                .isEqualTo(10L);
        assertThat(DbSpans.rowCount(span("s1")
                        .named("result-set")
                        .tags(Map.of("jdbc.row-count", "x"))
                        .build()))
                .isNull();
        assertThat(DbSpans.rowCount(span("s1")
                        .named("query")
                        .tags(Map.of("jdbc.row-count", "10"))
                        .build()))
                .isNull();
        assertThat(DbSpans.rowCount(span("s1").named("result-set").build())).isNull();
    }

    @Test
    void statementTagsAreTheOnesSqlReads() {
        assertThat(DbSpans.isStatementTag("db.query.text")).isTrue();
        assertThat(DbSpans.isStatementTag("db.statement")).isTrue();
        assertThat(DbSpans.isStatementTag("jdbc.query[3]")).isTrue();
        assertThat(DbSpans.isStatementTag("db.system")).isFalse();
        assertThat(DbSpans.isStatementTag("jdbc.row-count")).isFalse();
    }
}
