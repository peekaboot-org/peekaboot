package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Trace and span ids are the one thing on these surfaces a developer needs to move into
 * another tool - a log search, a ticket - so they are rendered in full, labelled, and
 * copy on click.
 *
 * <p>Clipboard access needs a secure context; http://localhost qualifies, so the real
 * {@code navigator.clipboard} path is what runs here rather than the execCommand fallback.
 */
class CopyableIdIT extends PlaywrightTestBase {

    private static final int TRACE_ID_LENGTH = 32;

    @BeforeEach
    void grantClipboard() {
        page.context().grantPermissions(List.of("clipboard-read", "clipboard-write"));
    }

    private void openPageWithToolbar() {
        page.navigate(baseUrl + "/");
        toolbar.traceId();
    }

    @Test
    @DisplayName("the toolbar shows the whole trace id, labelled - a truncated id cannot be pasted anywhere")
    void toolbarShowsTheFullTraceIdWithItsLabel() {
        openPageWithToolbar();

        String text = toolbar.text("#pk-trace");
        String copied = toolbar.traceId();

        assertThat(text)
                .as("the id is prefixed so it reads as a trace id, not a bare hex string")
                .contains("traceId");
        assertThat(text).as("no ellipsis - the id is shown in full").doesNotContain("...");
        assertThat(copied).hasSize(TRACE_ID_LENGTH);
        assertThat(text).contains(copied);
    }

    @Test
    @DisplayName("clicking the id copies it and does not open the overlay")
    void clickingTheTraceIdCopiesItWithoutOpeningTheOverlay() {
        openPageWithToolbar();
        String traceId = toolbar.traceId();

        toolbar.click("#pk-trace .pk-copy");

        toolbar.waitUntil("root => root.querySelector('#pk-trace .pk-copy').classList.contains('pk-copy--copied')");
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

        boolean nested = (boolean) toolbar.evaluate("root => !!root.querySelector('.pk-toolbar__open .pk-copy')");

        assertThat(nested)
                .as("a button inside a button is invalid, and ARIA treats a button's children as "
                        + "presentational - the copy control would be pruned from the accessibility tree")
                .isFalse();
    }

    @Test
    @DisplayName("the copy control carries an accessible name naming both the action and the id")
    void copyControlHasAnAccessibleName() {
        openPageWithToolbar();

        String label = (String)
                toolbar.evaluate("root => root.querySelector('#pk-trace .pk-copy').getAttribute('aria-label')");
        String traceId = toolbar.traceId();

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

    /**
     * Opens the overlay for a trace guaranteed to carry at least one log (the root
     * span's own PersonController.index() error log - see TraceOverlayIT's
     * logsFilterChipUsesTheContrastTunedForeground, which relies on the same /?error=true
     * path), then switches to the Logs tab.
     */
    private void openLogsTabWithAtLeastOneLogRow() {
        page.navigate(baseUrl + "/?error=true");
        toolbar.openOverlay();
        overlay.openLogsTab();
    }

    /**
     * The span tree (Spans tab) dropped the copyable span id - a full id on every row
     * made the tree too crowded. This is its new home: every Logs tab row now carries
     * its span's full id next to the name button that filters to it.
     */
    @Test
    @DisplayName("the Logs tab shows each row's full span id as a labelled copy control")
    void logsTableRendersCopyableSpanIds() {
        openLogsTabWithAtLeastOneLogRow();

        String label = (String) overlay.evaluate(
                "root => root.querySelector('.pk-log__span-cell .pk-copy').getAttribute('aria-label')");
        String copied =
                (String) overlay.evaluate("root => root.querySelector('.pk-log__span-cell .pk-copy').dataset.pkCopy");
        String spanIdOnRow = (String) overlay.evaluate("root => root.querySelector('.pk-log__span').dataset.spanId");

        assertThat(label).as("labelled, not a bare hex string").isEqualTo("Copy spanId " + copied);
        assertThat(copied).as("the same span the row's filter button targets").isEqualTo(spanIdOnRow);
    }

    @Test
    @DisplayName("clicking a log row's span id copies it and does not also trigger the row's span filter")
    void clickingTheLogSpanIdCopiesItWithoutFiltering() {
        openLogsTabWithAtLeastOneLogRow();

        overlay.click(".pk-log__span-cell .pk-copy");

        overlay.waitUntil(
                "root => root.querySelector('.pk-log__span-cell .pk-copy').classList.contains('pk-copy--copied')");
        Object filterChip = overlay.evaluate("root => root.querySelector('.pk-logs-filter-span')");
        assertThat(filterChip)
                .as("copying an id is not a request to also filter by it - same capture-phase handler as the toolbar")
                .isNull();
    }
}
