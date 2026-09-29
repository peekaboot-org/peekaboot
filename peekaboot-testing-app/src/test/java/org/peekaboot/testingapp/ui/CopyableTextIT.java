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
        assertThat(page.textContent(".pk-copy__status")).isEqualTo("Copied");
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
        assertThat(page.textContent(".pk-copy__status")).isEqualTo(state);
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
