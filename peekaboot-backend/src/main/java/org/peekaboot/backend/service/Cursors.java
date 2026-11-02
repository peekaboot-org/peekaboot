package org.peekaboot.backend.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.problem.ApiProblemException;

/**
 * The {@code next} values the listing endpoints hand out and read back as {@code cursor}. A cursor
 * names the last row a page served by its position in the listing, so rows that arrive or leave
 * between two pages shift no other row. A log search cursor names the last line a page served by
 * the store's {@code sequence} for it. Both carry the store's numbering first: a restart numbers
 * every row and line anew, so a cursor from before it is refused. On the wire it is base64url of a
 * kind letter and numbers, opaque to clients.
 */
final class Cursors {

    private static final String ROW = "t";
    private static final String LOG = "l";
    private static final String SEPARATOR = ".";
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,18}");

    private Cursors() {}

    static String ofRow(long numbering, RowPosition position) {
        return encode(ROW, numbering, position.listedAt(), position.listing(), position.entry());
    }

    /** Null where the request sent none; refuses a value the trace listing did not issue. */
    @Nullable
    static Issued<RowPosition> row(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        long[] values = decode(cursor, ROW, 4);
        if (values[0] < 1 || values[1] < 0 || values[2] < 0 || values[3] < RowPosition.WHOLE_TRACE) {
            throw refused(cursor);
        }
        return new Issued<>(values[0], new RowPosition(values[1], values[2], values[3]), cursor);
    }

    static String ofLog(long numbering, long sequence) {
        return encode(LOG, numbering, sequence);
    }

    /** Null where the request sent none; refuses a value the log search did not issue. */
    @Nullable
    static Issued<Long> log(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        long[] values = decode(cursor, LOG, 2);
        if (values[0] < 1 || values[1] < 0) {
            throw refused(cursor);
        }
        return new Issued<>(values[0], values[1], cursor);
    }

    private static String encode(String kind, long... values) {
        StringBuilder text = new StringBuilder(kind);
        for (long value : values) {
            text.append(SEPARATOR).append(value);
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(text.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Checked before decoding, so a value no cursor can be is refused without an exception to rethrow.
     * Only the exact text {@link #encode} writes for {@code kind} reads back, so a value of another
     * kind, a padded number or a stray space is refused too.
     */
    private static long[] decode(String cursor, String kind, int count) {
        if (!BASE64URL.matcher(cursor).matches() || cursor.length() % 4 == 1) {
            throw refused(cursor);
        }
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII)
                .split(Pattern.quote(SEPARATOR), -1);
        if (parts.length != count + 1) {
            throw refused(cursor);
        }
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            if (!NUMBER.matcher(parts[i + 1]).matches()) {
                throw refused(cursor);
            }
            values[i] = Long.parseLong(parts[i + 1]);
        }
        if (!encode(kind, values).equals(cursor)) {
            throw refused(cursor);
        }
        return values;
    }

    private static ApiProblemException refused(String cursor) {
        return ApiProblemException.badParameter(
                "cursor",
                "Unknown cursor '" + cursor
                        + "'. Pass the next value of an earlier response from this endpoint as it was.");
    }

    /**
     * A cursor as read back: the numbering of the store that handed it out and the position it names
     * there.
     */
    record Issued<T>(long numbering, T position, String cursor) {

        /** The position, refused where another store numbered it, as a restart does. */
        T under(long current) {
            if (numbering != current) {
                throw ApiProblemException.badParameter(
                        "cursor",
                        "Cursor '" + cursor + "' was handed out before the application restarted,"
                                + " which numbered every row and line anew. Read the first page again without a cursor.");
            }
            return position;
        }
    }
}
