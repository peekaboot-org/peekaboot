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
}
