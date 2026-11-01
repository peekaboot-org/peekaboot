package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SqlNormalizerTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "select * from person where id = 42                 | select * from person where id = ?",
                "select * from person where name = 'O''Brien'       | select * from person where name = ?",
                "select * from t where price > 19.99 and qty < 1e3  | select * from t where price > ? and qty < ?",
                "select * from t where data = X'0A0B'               | select * from t where data = ?",
                "select co1_0.id from customer_order co1_0          | select co1_0.id from customer_order co1_0",
                "select * from t where id = ? and other = $1        | select * from t where id = ? and other = $1",
                "'  select\n   *\tfrom t  '                         | select * from t",
                "select * from t where name = 'unterminated         | select * from t where name = 'unterminated"
            })
    void replacesLiteralsAndCollapsesWhitespace(String sql, String normalized) {
        assertThat(SqlNormalizer.normalize(sql)).isEqualTo(normalized);
    }

    /** A seed row or an inlined JSON body can carry a literal far longer than a stack has frames. */
    @Test
    void replacesALiteralOfAHundredThousandCharacters() {
        String body = "x".repeat(50_000) + "''" + "y".repeat(50_000);

        assertThat(SqlNormalizer.normalize("insert into page (body) values ('" + body + "')"))
                .isEqualTo("insert into page (body) values (?)");
    }

    @Test
    void leavesAnUnterminatedQuoteWithALongTailAsWritten() {
        String sql = "select * from t where name = 'unterminated " + "a".repeat(100_000);

        assertThat(SqlNormalizer.normalize(sql)).isEqualTo(sql);
    }

    /**
     * Each doubled quote of an unterminated literal could open a scan to the end of the text, which
     * is quadratic. A linear pass takes milliseconds, the quadratic one minutes.
     */
    @Test
    void readsAnUnterminatedQuoteFullOfDoubledQuotesInOnePass() {
        String sql = "select '" + "a''".repeat(100_000);

        String normalized = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> SqlNormalizer.normalize(sql));

        assertThat(normalized).isEqualTo(sql);
    }
}
