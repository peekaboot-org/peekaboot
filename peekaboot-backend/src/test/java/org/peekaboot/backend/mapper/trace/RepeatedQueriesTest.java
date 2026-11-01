package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SpanNodes.node;

import io.micrometer.tracing.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.domain.trace.IssueType;
import org.peekaboot.backend.domain.trace.IssueUnit;
import org.peekaboot.backend.domain.trace.RepeatedQueryIssue;
import org.peekaboot.backend.domain.trace.SpanNode;
import org.peekaboot.backend.domain.trace.SqlStatement;

class RepeatedQueriesTest {

    private static SpanNode query(String spanId, String sql, long durationMs) {
        return node(spanId)
                .named("query")
                .kind(Span.Kind.CLIENT)
                .durationMs(durationMs)
                .statement(new SqlStatement(sql, null, List.of(), 0))
                .build();
    }

    private static SpanNode connection(String spanId) {
        return node(spanId)
                .named("connection")
                .kind(Span.Kind.CLIENT)
                .tags(Map.of("jdbc.datasource.name", "dataSource"))
                .build();
    }

    private static SpanNode resultSet(String spanId) {
        return node(spanId)
                .named("result-set")
                .kind(Span.Kind.CLIENT)
                .tags(Map.of("jdbc.row-count", "1"))
                .build();
    }

    @Test
    void flagsAStatementTheSpanRanAtTheThreshold() {
        List<SpanNode> lines = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            lines.add(query("q" + i, "select * from order_line where order_id = " + i, 2));
        }
        SpanNode parent =
                node("handler").named("OrderService.listOrders").children(lines).build();

        List<RepeatedQueryIssue> issues = RepeatedQueries.under(parent, 5);

        assertThat(issues)
                .containsExactly(new RepeatedQueryIssue(
                        IssueType.REPEATED_QUERY,
                        IssueSeverity.WARNING,
                        "handler",
                        5,
                        5,
                        IssueUnit.QUERIES,
                        "This span ran the same statement 5 times, 10 ms in total.",
                        "select * from order_line where order_id = ?",
                        5,
                        10,
                        List.of("q1", "q2", "q3", "q4", "q5")));
    }

    @Test
    void staysQuietBelowTheThreshold() {
        SpanNode parent = node("handler")
                .children(List.of(
                        query("q1", "select 1 from t where id = 1", 1), query("q2", "select 1 from t where id = 2", 1)))
                .build();

        assertThat(RepeatedQueries.under(parent, 3)).isEmpty();
    }

    /** Outside a transaction each query opens its own connection, recorded beside the query with its result set. */
    @Test
    void groupsQueriesBesideTheirConnectionSpans() {
        List<SpanNode> spans = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            spans.add(connection("c" + i));
            spans.add(query("q" + i, "select * from person where id = " + i, 1));
            spans.add(resultSet("r" + i));
        }
        SpanNode parent = node("handler").children(spans).build();

        assertThat(RepeatedQueries.under(parent, 5)).singleElement().satisfies(issue -> {
            assertThat(issue.statement()).isEqualTo("select * from person where id = ?");
            assertThat(issue.spanIds()).containsExactly("q1", "q2", "q3", "q4", "q5");
        });
    }

    /** The span that ran the queries carries the issue, so its ancestors do not repeat it. */
    @Test
    void countsOnlyTheDirectChildren() {
        List<SpanNode> lines = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            lines.add(query("q" + i, "select * from order_line where order_id = " + i, 1));
        }
        SpanNode loop = node("loop").children(lines).build();

        assertThat(RepeatedQueries.under(node("handler").children(List.of(loop)).build(), 5))
                .isEmpty();
    }

    @Test
    void groupsEachStatementOnItsOwn() {
        List<SpanNode> mixed = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            mixed.add(query("a" + i, "select * from a where id = " + i, 1));
            mixed.add(query("b" + i, "select * from b where id = " + i, 1));
        }
        SpanNode parent = node("handler").children(mixed).build();

        assertThat(RepeatedQueries.under(parent, 2))
                .extracting(RepeatedQueryIssue::statement)
                .containsExactly("select * from a where id = ?", "select * from b where id = ?");
    }

    /** A Redis command or a query recorded without SQL has no statement to compare. */
    @Test
    void ignoresQueriesWithoutAStatement() {
        List<SpanNode> commands = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            commands.add(node("r" + i)
                    .named("get")
                    .kind(Span.Kind.CLIENT)
                    .tags(Map.of("db.system", "redis"))
                    .build());
        }

        assertThat(RepeatedQueries.under(node("handler").children(commands).build(), 2))
                .isEmpty();
    }
}
