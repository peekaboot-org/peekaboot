package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace and span ids are the one thing on these surfaces a developer needs to move into
 * another tool - a log search, a ticket - so they are rendered in full, labelled, and
 * copy on click.
 *
 * <p>Clipboard access needs a secure context; http://localhost qualifies, so the real
 * {@code navigator.clipboard} path is what runs here rather than the execCommand fallback.
 */
class CopyableIdTest extends PlaywrightTestBase {

    private static final int TRACE_ID_LENGTH = 32;

    private static final String TOOLBAR_COPY =
            "document.getElementById('peekaboot-toolbar-host').shadowRoot.querySelector('#pk-trace .pk-copy')";

    @BeforeEach
    void grantClipboard() {
        page.context().grantPermissions(List.of("clipboard-read", "clipboard-write"));
    }

    private void openPageWithToolbar() {
        page.navigate(baseUrl + "/");
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction(
                "() => document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'",
                null, new Page.WaitForFunctionOptions().setTimeout(15000));
    }

    @Test
    @DisplayName("the toolbar shows the whole trace id, labelled - a truncated id cannot be pasted anywhere")
    void toolbarShowsTheFullTraceIdWithItsLabel() {
        openPageWithToolbar();

        String text = (String) page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent");
        String copied = (String) page.evaluate("() => " + TOOLBAR_COPY + ".dataset.pkCopy");

        assertThat(text).as("the id is prefixed so it reads as a trace id, not a bare hex string")
                .contains("traceId");
        assertThat(text).as("no ellipsis - the id is shown in full").doesNotContain("...");
        assertThat(copied).hasSize(TRACE_ID_LENGTH);
        assertThat(text).contains(copied);
    }

    @Test
    @DisplayName("clicking the id copies it and does not open the overlay")
    void clickingTheTraceIdCopiesItWithoutOpeningTheOverlay() {
        openPageWithToolbar();
        String traceId = (String) page.evaluate("() => " + TOOLBAR_COPY + ".dataset.pkCopy");

        page.evaluate("() => " + TOOLBAR_COPY + ".click()");

        page.waitForFunction("() => " + TOOLBAR_COPY + ".classList.contains('pk-copy--copied')");
        assertThat((String) page.evaluate("() => navigator.clipboard.readText()"))
                .as("the full id reaches the clipboard")
                .isEqualTo(traceId);
        assertThat(page.querySelector("#peekaboot-trace-overlay"))
                .as("the whole toolbar bar opens the overlay on click; copying an id must not "
                  + "also trigger it, which needs the copy handler to run in the capture phase")
                .isNull();
    }

    @Test
    @DisplayName("the copy control is a sibling of the open button, never a descendant")
    void copyControlIsNotNestedInsideTheOpenButton() {
        openPageWithToolbar();

        boolean nested = (boolean) page.evaluate(
                "() => !!document.getElementById('peekaboot-toolbar-host')"
              + ".shadowRoot.querySelector('.pk-toolbar__open .pk-copy')");

        assertThat(nested)
                .as("a button inside a button is invalid, and ARIA treats a button's children as "
                  + "presentational - the copy control would be pruned from the accessibility tree")
                .isFalse();
    }

    @Test
    @DisplayName("the copy control carries an accessible name naming both the action and the id")
    void copyControlHasAnAccessibleName() {
        openPageWithToolbar();

        String label = (String) page.evaluate("() => " + TOOLBAR_COPY + ".getAttribute('aria-label')");
        String traceId = (String) page.evaluate("() => " + TOOLBAR_COPY + ".dataset.pkCopy");

        assertThat(label).isEqualTo("Copy traceId " + traceId);
    }

    @Test
    @DisplayName("trace ids in the dashboard list are copy controls too")
    void traceListRendersCopyableIds() {
        page.navigate(baseUrl + "/");
        openDashboard();
        page.click(".pk-tab[data-tab='traces']");
        page.waitForSelector("#traces-list .pk-trace-item");

        assertThat(page.querySelectorAll("#traces-list .pk-trace-item .pk-copy"))
                .as("every listed trace exposes its id for copying")
                .isNotEmpty();
        assertThat((String) page.evaluate(
                "() => document.querySelector('#traces-list .pk-trace-item .pk-copy').dataset.pkCopy"))
                .as("the row shows a shortened id but copies the whole one")
                .hasSize(TRACE_ID_LENGTH);
    }
}
