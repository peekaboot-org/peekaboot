package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HibernateSqlFormatterTest {

    private final HibernateSqlFormatter formatter = new HibernateSqlFormatter();

    /** Hibernate's format_sql layout, minus the line break BASIC opens every statement with. */
    @Test
    void laysAStatementOutTheWayHibernatesSqlLogDoes() {
        assertThat(formatter.format("select p1_0.id,p1_0.email from person p1_0 where p1_0.id=?"))
                .isEqualTo("    select\n        p1_0.id,\n        p1_0.email \n    from\n"
                        + "        person p1_0 \n    where\n        p1_0.id=?");
    }

    /** Formatted as one string, the second statement would start mid-line after the first one's semicolon. */
    @Test
    void formatsEachStatementOfABatchOnItsOwn() {
        String single = "    insert \n    into\n        person\n        (email, id) \n    values\n        (?, ?)";

        assertThat(formatter.format(
                        "insert into person (email,id) values (?,?);\ninsert into person (email,id) values (?,?)"))
                .isEqualTo(single + ";\n" + single);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "select 'it''s; here' from dual",
                "update person set email=?,first_name=? where id=?",
                "select a from t where b in (select c from d) and e = '******'"
            })
    void onlyEverMovesWhitespace(String sql) {
        assertThat(formatter.format(sql).replaceAll("\\s", "")).isEqualTo(sql.replaceAll("\\s", ""));
    }
}
