package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The text copy control copies whatever its source answers at click time, through the same
 * delegated listener and with the same feedback as the id controls CopyableIdIT covers.
 */
class CopyableTextIT extends PlaywrightTestBase {

    /** The live region of the document the controls are bound to. */
    private static final String STATUS_REGION = "body > .pk-copy__status";

    private static final String BUILD_CONTROL = """
            (() => {
                window.shownText = 'select 1';
                m.bindCopyables(document);
                document.body.append(m.copyableText(() => window.shownText, {label: 'SQL'}));
                window.shownText = 'select 2';
                return document.querySelector('.pk-copy').getAttribute('aria-label');
            })()
            """;

    // Reading the clipboard back needs the clipboard-read permission, which only Chromium has.
    @Tag("chromium-only")
    @Test
    void copiesWhatItsSourceAnswersWhenClicked() {
        page.context().grantPermissions(List.of("clipboard-read", "clipboard-write"));
        importModule("shared/copyable.js", BUILD_CONTROL);

        page.click(".pk-copy");

        page.waitForSelector(".pk-copy.pk-copy--copied");
        assertThat(page.textContent(STATUS_REGION)).isEqualTo("Copied");
        assertThat((String) page.evaluate("() => navigator.clipboard.readText()"))
                .isEqualTo("select 2");
    }

    @Test
    void namesWhatItCopies() {
        assertThat(importModule("shared/copyable.js", BUILD_CONTROL)).isEqualTo("Copy SQL");
        assertThat(page.textContent(".pk-copy__label")).isEqualTo("Copy SQL");
    }

    /** The legacy path, where navigator.clipboard does not exist; either outcome is reported. */
    @Test
    void reportsTheOutcomeOnEveryEngine() {
        page.addInitScript("Object.defineProperty(window, 'isSecureContext', {get: () => false});");
        importModule("shared/copyable.js", BUILD_CONTROL);

        page.click(".pk-copy");

        page.waitForSelector(".pk-copy.pk-copy--copied, .pk-copy.pk-copy--failed");
        String state = page.isVisible(".pk-copy--copied") ? "Copied" : "Copy failed";
        assertThat(page.textContent(STATUS_REGION)).isEqualTo(state);
    }

    /** A region that arrives together with its text can go unannounced, so binding puts it there empty. */
    @Test
    void theRegionIsInPlaceBeforeTheFirstCopy() {
        importModule("shared/copyable.js", BUILD_CONTROL);

        assertThat(page.locator(STATUS_REGION).allTextContents()).containsExactly("");
    }

    /** A root that replaces its children takes the region out with them, and the next copy puts one back. */
    @Test
    void aRootThatReplacedItsChildrenGetsItsRegionBack() {
        importModule("shared/copyable.js", BUILD_CONTROL);
        holdFeedback();
        page.evaluate("() => document.body.replaceChildren(document.querySelector('.pk-copy'))");

        String result = copyAndAwaitFeedback(".pk-copy");

        assertThat(page.locator(STATUS_REGION).allTextContents()).containsExactly(result);
    }

    /**
     * Two copies inside one feedback period share the region. The first control's reset comes
     * due before the second's and leaves the second's announcement standing.
     */
    @Test
    void anEarlierControlsResetLeavesALaterAnnouncementStanding() {
        importModule("shared/copyable.js", """
                (() => {
                    m.bindCopyables(document);
                    for (const id of ['first', 'second']) {
                        const control = m.copyableText(() => id, {label: id});
                        control.id = id;
                        document.body.append(control);
                    }
                })()
                """);
        holdFeedback();

        copyAndAwaitFeedback("#first");
        page.clock().runFor(1000);
        String secondResult = copyAndAwaitFeedback("#second");
        page.clock().runFor(1000);

        assertThat(page.getAttribute("#first", "class")).isEqualTo("pk-copy");
        assertThat(page.textContent(STATUS_REGION)).isEqualTo(secondResult);

        page.clock().runFor(1000);

        assertThat(page.getAttribute("#second", "class")).isEqualTo("pk-copy");
        assertThat(page.textContent(STATUS_REGION)).isEmpty();
    }

    /** Stops the page's timers, so feedback stays up until the test runs the clock on. */
    private void holdFeedback() {
        page.clock().install();
        page.clock().pauseAt(System.currentTimeMillis() + 60_000);
    }

    /** Clicks the control, waits for its feedback and returns what the region should then read. */
    private String copyAndAwaitFeedback(String control) {
        page.evaluate("control => document.querySelector(control).click()", control);
        page.waitForSelector(control + ".pk-copy--copied, " + control + ".pk-copy--failed");
        return page.isVisible(control + ".pk-copy--copied") ? "Copied" : "Copy failed";
    }

    /**
     * The listener now claims every .pk-copy, and the empty id placeholder is one: it copies
     * nothing, shows no feedback and leaves the click to whatever contains it.
     */
    @Test
    void theEmptyIdPlaceholderStaysInert() {
        assertThat(importModule("shared/copyable.js", """
                        (() => {
                            m.bindCopyables(document);
                            let rowClicks = 0;
                            const row = document.createElement('div');
                            row.addEventListener('click', () => rowClicks++);
                            row.append(m.copyableId(null, {label: 'traceId'}));
                            document.body.append(row);
                            row.querySelector('.pk-copy--empty').click();
                            return new Promise(resolve => setTimeout(
                                    () => resolve([rowClicks, row.querySelector('.pk-copy--empty').className]), 100));
                        })()
                        """)).isEqualTo(List.of(1, "pk-copy pk-copy--empty"));
    }
}
