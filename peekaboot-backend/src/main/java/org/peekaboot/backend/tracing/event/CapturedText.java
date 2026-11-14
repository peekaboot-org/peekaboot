package org.peekaboot.backend.tracing.event;

import org.peekaboot.backend.text.CountFormat;

/** The one bound on a text Peekaboot captures whole: a span's tag value, an exception's message, a log line's message. */
public final class CapturedText {

    /**
     * Far above an ordinary message or tag value. A logged payload or a generated statement
     * past it is cut, so a single record cannot dominate the trace store's retention.
     */
    public static final int MAX_LENGTH = 16_000;

    /**
     * How far a cut backs off to end before whitespace: past a provider's token or a URL's
     * credentials. A text with no whitespace that close keeps its full head.
     */
    static final int MAX_BACK_OFF = 1_024;

    private CapturedText() {}

    /**
     * {@code text} itself within {@link #MAX_LENGTH}, else its head and a note saying how many
     * characters were left out. Masking runs on read, and a cut inside a credential would leave
     * a head no pattern recognises any more. So the head ends before the last whitespace within
     * {@link #MAX_BACK_OFF} of the limit, and at the limit where there is none.
     */
    public static String cap(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }
        String head = headOfWholeWords(text);
        return head + "... " + CountFormat.of(text.length() - head.length(), "character") + " omitted";
    }

    private static String headOfWholeWords(String text) {
        for (int end = MAX_LENGTH; end >= MAX_LENGTH - MAX_BACK_OFF; end--) {
            if (Character.isWhitespace(text.charAt(end))) {
                return text.substring(0, end);
            }
        }
        return head(text, MAX_LENGTH);
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
