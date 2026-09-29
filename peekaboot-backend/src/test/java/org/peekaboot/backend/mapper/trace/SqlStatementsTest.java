package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.query;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.masking.MaskingEngine;

class SqlStatementsTest {

    private static final String GITHUB_TOKEN = "ghp_" + "a".repeat(36);

    private final SqlStatements withHibernate = new SqlStatements(new MaskingEngine(), new HibernateSqlFormatter());

    @Test
    void servesTheRawStatementAloneWithoutAFormatter() {
        SqlStatement statement = new SqlStatements(new MaskingEngine(), null)
                .of(jdbcQuery("q1", "select 1").build());

        assertThat(statement).isEqualTo(new SqlStatement("select 1", null, List.of()));
    }

    /** Formatting runs on the raw text, so masking sees the same credential in both. */
    @Test
    void formatsTheRawSqlThenMasksBothForms() {
        SqlStatement statement =
                withHibernate.of(jdbcQuery("q1", "select 'https://admin:hunter2@example.com/x' from dual")
                        .build());

        assertThat(statement.text()).isEqualTo("select 'https://******@example.com/x' from dual");
        assertThat(statement.formatted())
                .isEqualTo("    select\n        'https://******@example.com/x' \n    from\n        dual");
    }

    /** BASIC keeps a literal whole, so the output above cannot tell the order apart; what the formatter receives can. */
    @Test
    void handsTheFormatterTheRawSql() {
        List<String> received = new ArrayList<>();
        SqlFormatter recording = sql -> {
            received.add(sql);
            return sql;
        };

        new SqlStatements(new MaskingEngine(), recording)
                .of(jdbcQuery("q1", "select 'https://admin:hunter2@example.com/x' from dual")
                        .build());

        assertThat(received).containsExactly("select 'https://admin:hunter2@example.com/x' from dual");
    }

    @Test
    void masksEachParameterTheWayItMasksTheSql() {
        SqlStatement statement = withHibernate.of(jdbcQuery("q1", "insert into tokens (id, value) values (?, ?)")
                .tag(DbSpans.PARAMETERS_TAG, "[[\"42\",\"'" + GITHUB_TOKEN + "'\"],[\"43\",\"'plain'\"]]")
                .build());

        assertThat(statement.parameters()).containsExactly(List.of("42", "'******'"), List.of("43", "'plain'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"a\":1}", "null", "[null]", "[[null]]", "[\"x\"]", "[[\"x\"],1]"})
    void aMalformedParametersTagYieldsNoParameters(String tag) {
        SqlStatement statement = withHibernate.of(
                jdbcQuery("q1", "select ?").tag(DbSpans.PARAMETERS_TAG, tag).build());

        assertThat(statement.text()).isEqualTo("select ?");
        assertThat(statement.parameters()).isEmpty();
    }

    /** Hibernate's BasicFormatterImpl throws NoSuchElementException on unbalanced closing parentheses. */
    @Test
    void aStatementTheFormatterCannotLayOutStillShowsRaw() {
        SqlStatement statement = withHibernate.of(jdbcQuery("q1", ")))").build());

        assertThat(statement).isEqualTo(new SqlStatement(")))", null, List.of()));
    }

    @Test
    void aQueryThatRecordedNoSqlHasNoStatement() {
        assertThat(withHibernate.of(query("q1").tags(Map.of("db.system", "h2")).build()))
                .isNull();
    }

    @Test
    void aSpanThatIsNoQueryHasNoStatement() {
        assertThat(withHibernate.of(
                        span("s1").tags(Map.of("db.statement", "select 1")).build()))
                .isNull();
    }
}
