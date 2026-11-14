package org.peekaboot.backend.tracing.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CapturedTextTest {

    @Test
    void aTextWithinTheCapIsKeptAsItIs() {
        String text = "x".repeat(16_000);

        assertThat(CapturedText.cap(text)).isSameAs(text);
    }

    @Test
    void aTextPastTheCapIsCutAndSaysHowMuchWasLeftOut() {
        assertThat(CapturedText.cap("x".repeat(16_007))).isEqualTo("x".repeat(16_000) + "... 7 characters omitted");
    }

    @Test
    void oneCharacterLeftOutReadsInTheSingular() {
        assertThat(CapturedText.cap("x".repeat(16_001))).endsWith("x... 1 character omitted");
    }

    /** The emoji is two chars, the last of the cap and the first past it; both go, and both are counted. */
    @Test
    void aCutNeverSplitsASurrogatePair() {
        assertThat(CapturedText.cap("x".repeat(15_999) + "😀" + "tail"))
                .isEqualTo("x".repeat(15_999) + "... 6 characters omitted");
    }

    /** Cut at the cap itself, the token's first 19 characters would be left where no pattern recognises them. */
    @Test
    void aCredentialStraddlingTheCapNeverShowsItsHead() {
        String token = "ghp" + "_16C7e42F292c6912E7710c838347Ae178B4a";

        assertThat(CapturedText.cap("x".repeat(15_980) + " " + token + " tail"))
                .isEqualTo("x".repeat(15_980) + "... 46 characters omitted");
    }

    @Test
    void aWordThatEndsAtTheCapIsKept() {
        String endsAtTheCap = "x".repeat(15_990) + " " + "y".repeat(9);

        assertThat(CapturedText.cap(endsAtTheCap + " tail")).isEqualTo(endsAtTheCap + "... 5 characters omitted");
    }

    @Test
    void aCutBacksOffToWhitespaceAtTheFarEndOfItsReach() {
        String kept = "x".repeat(CapturedText.MAX_LENGTH - CapturedText.MAX_BACK_OFF);

        assertThat(CapturedText.cap(kept + " " + "y".repeat(CapturedText.MAX_BACK_OFF + 6)))
                .isEqualTo(kept + "... 1031 characters omitted");
    }

    /** One character further back, the whitespace is out of reach and the text keeps its full head. */
    @Test
    void whitespaceBeyondTheReachOfACutLeavesItAtTheCap() {
        String head = "x".repeat(CapturedText.MAX_LENGTH - CapturedText.MAX_BACK_OFF - 1)
                + " "
                + "y".repeat(CapturedText.MAX_BACK_OFF);

        assertThat(CapturedText.cap(head + "y".repeat(7))).isEqualTo(head + "... 7 characters omitted");
    }
}
