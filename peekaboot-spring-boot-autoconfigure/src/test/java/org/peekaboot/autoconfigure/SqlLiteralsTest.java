package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlLiteralsTest {

    static Stream<Arguments> literals() {
        return Stream.of(
                Arguments.of(null, "NULL"),
                Arguments.of(42, "42"),
                Arguments.of(42L, "42"),
                Arguments.of(1.5d, "1.5"),
                Arguments.of(new BigDecimal("1E+3"), "1000"),
                Arguments.of(true, "true"),
                Arguments.of("O'Brien", "'O''Brien'"),
                Arguments.of('x', "'x'"),
                Arguments.of(
                        UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                        "'123e4567-e89b-12d3-a456-426614174000'"),
                Arguments.of(DayOfWeek.MONDAY, "'MONDAY'"),
                Arguments.of(Timestamp.valueOf("2026-09-29 10:15:30"), "'2026-09-29T10:15:30'"),
                Arguments.of(java.sql.Date.valueOf("2026-09-29"), "'2026-09-29'"),
                Arguments.of(Time.valueOf("10:15:30"), "'10:15:30'"),
                Arguments.of(LocalDate.of(2026, 9, 29), "'2026-09-29'"),
                Arguments.of(Instant.parse("2026-09-29T08:15:30Z"), "'2026-09-29T08:15:30Z'"),
                Arguments.of(new byte[] {0x0A, (byte) 0xFF}, "X'0AFF'"),
                Arguments.of(List.of(1, 2), "'[1, 2]'"));
    }

    @ParameterizedTest
    @MethodSource("literals")
    void rendersEachValueAsTheLiteralAReaderWouldType(Object value, String literal) {
        assertThat(SqlLiterals.render(value)).isEqualTo(literal);
    }

    /**
     * A stray giant CLOB bind value should not bloat a trace or its OTLP export. The marker is
     * a trailing SQL block comment, not text spliced after the closing quote, so a truncated
     * literal substituted into query text (Task 6) still parses.
     */
    @Test
    void capsALongStringAtTheSourceLengthAndMarksWhatWasDroppedAsATrailingComment() {
        String value = "x".repeat(SqlLiterals.MAX_LITERAL_LENGTH + 7);

        String literal = SqlLiterals.render(value);

        assertThat(literal)
                .isEqualTo("'" + "x".repeat(SqlLiterals.MAX_LITERAL_LENGTH) + "' /* 7 characters omitted */");
    }

    /**
     * The cap applies to the source bytes, not the hex string they render as - hex-encoding
     * doubles length. Hex cannot carry the marker inside the quotes the way text can, so it too
     * gets a trailing block comment.
     */
    @Test
    void capsLongBytesBeforeHexEncodingAndMarksWhatWasDroppedAsATrailingComment() {
        byte[] value = new byte[SqlLiterals.MAX_LITERAL_LENGTH + 3];

        String literal = SqlLiterals.render(value);

        assertThat(literal).isEqualTo("X'" + "00".repeat(SqlLiterals.MAX_LITERAL_LENGTH) + "' /* 3 bytes omitted */");
    }
}
