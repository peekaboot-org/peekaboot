package org.peekaboot.backend.tracing.event;

import java.util.function.IntPredicate;
import org.peekaboot.backend.text.CountFormat;

/** The one bound on a text Peekaboot captures whole: a span's tag value, an exception's message, a log line's message. */
public final class CapturedText {

    /**
     * Far above an ordinary message or tag value. A logged payload or a generated statement
     * past it is cut, so a single record cannot dominate the trace store's retention.
     */
    public static final int MAX_LENGTH = 16_000;

    /**
     * How far a cut backs off to end before whitespace, a quote or a comma: past a provider's
     * token or a URL's credentials. A text with none of them that close keeps its full head,
     * and so does one with an {@code @} between its last quote or comma and the limit.
     */
    static final int MAX_BACK_OFF = 1_024;

    /** What a value sits between in text without whitespace, compact JSON above all. */
    private static final String VALUE_DELIMITERS = "\"',";

    private CapturedText() {}

    /**
     * {@code text} itself within {@link #MAX_LENGTH}, else its head and a note saying how many
     * characters were left out. Masking runs on read, and a cut inside a credential would leave
     * a head no pattern recognises any more. So the head ends before the last whitespace within
     * {@link #MAX_BACK_OFF} of the limit. A quote or a comma ends it only where that reach holds
     * no whitespace and no {@code @} follows before the limit, since a URL's password may hold
     * either and is masked by the {@code @} behind it. Otherwise the head ends at the limit.
     */
    public static String cap(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        String head = headOfWholeWords(text);
        return head + "... " + CountFormat.of(text.length() - head.length(), "character") + " omitted";
    }

    private static String headOfWholeWords(String text) {
        int whitespace = lastWithinReach(text, Character::isWhitespace);
        if (whitespace >= 0) {
            return text.substring(0, whitespace);
        }
        int delimiter = lastWithinReach(text, character -> VALUE_DELIMITERS.indexOf(character) >= 0);
        boolean endsAValue = delimiter >= 0 && !mayLieInAUrlsCredentials(text, delimiter);
        return endsAValue ? text.substring(0, delimiter) : head(text, MAX_LENGTH);
    }

    /** A cut ahead of an {@code @} that the full head would keep costs a URL's password its mask. */
    private static boolean mayLieInAUrlsCredentials(String text, int delimiter) {
        return text.lastIndexOf('@', MAX_LENGTH - 1) > delimiter;
    }

    private static int lastWithinReach(String text, IntPredicate boundary) {
        for (int index = MAX_LENGTH; index >= MAX_LENGTH - MAX_BACK_OFF; index--) {
            if (boundary.test(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    /** The first {@code maxLength} characters, one fewer where the last would be half of a surrogate pair. */
    public static String head(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        boolean splitsAPair = Character.isSurrogatePair(text.charAt(maxLength - 1), text.charAt(maxLength));
        return text.substring(0, splitsAPair ? maxLength - 1 : maxLength);
    }
}
