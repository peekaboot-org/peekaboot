package org.peekaboot.autoconfigure;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.HexFormat;

/**
 * A JDBC bind value as the SQL literal a reader would type for it. Display only: the
 * literal is never executed, so a dialect that spells a value differently costs accuracy,
 * never safety.
 */
final class SqlLiterals {

    static final String NULL = "NULL";

    /**
     * A literal past this many source characters (or bytes, for {@code byte[]}) is cut and
     * marked with the count dropped; a stray giant BLOB or CLOB bind value should not bloat a
     * trace or its OTLP export.
     */
    static final int MAX_LITERAL_LENGTH = 1000;

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private SqlLiterals() {}

    static String render(Object value) {
        return switch (value) {
            case null -> NULL;
            case BigDecimal decimal -> decimal.toPlainString();
            case Number number -> number.toString();
            case Boolean bool -> bool.toString();
            case byte[] bytes -> hex(bytes);
            default -> quoted(text(value));
        };
    }

    /** Dates and times as ISO-8601; the JDBC types' own toString is the JDBC escape format. */
    @SuppressWarnings("JavaUtilDate") // the driver hands these types in; converting them to java.time is the point
    private static String text(Object value) {
        return switch (value) {
            case Timestamp timestamp -> timestamp.toLocalDateTime().toString();
            case Date date -> date.toLocalDate().toString();
            case Time time -> time.toLocalTime().toString();
            default -> value.toString();
        };
    }

    /**
     * Capped before quoting: the source text, not the escaped-and-quoted output, is what a huge
     * value inflates. The marker is a trailing SQL block comment, not text spliced after the
     * closing quote, so a truncated literal substituted into query text still parses.
     */
    private static String quoted(String text) {
        if (text.length() <= MAX_LITERAL_LENGTH) {
            return "'" + text.replace("'", "''") + "'";
        }
        String kept = text.substring(0, MAX_LITERAL_LENGTH).replace("'", "''");
        return "'" + kept + "' /* " + (text.length() - MAX_LITERAL_LENGTH) + " characters omitted */";
    }

    /**
     * Capped before hex-encoding: hex-encoding doubles length, so capping the output string
     * would let twice as many source bytes through. Hex cannot carry the marker inside the
     * quotes the way text can, so it too gets a trailing block comment.
     */
    private static String hex(byte[] bytes) {
        if (bytes.length <= MAX_LITERAL_LENGTH) {
            return "X'" + HEX.formatHex(bytes) + "'";
        }
        String kept = HEX.formatHex(bytes, 0, MAX_LITERAL_LENGTH);
        return "X'" + kept + "' /* " + (bytes.length - MAX_LITERAL_LENGTH) + " bytes omitted */";
    }
}
