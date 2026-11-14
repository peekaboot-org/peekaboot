package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
        // Only Chromium's Playwright build knows these permission names - Firefox and WebKit
        // reject them outright - and every test here but one asserts on the DOM alone.
        if (isChromium()) {
            page.context().grantPermissions(List.of("clipboard-read", "clipboard-write"));
        }
    }

    private void openPageWithToolbar() {
        openPersonsPage();
        toolbar.traceId();
    }

    /**
     * Opens the overlay for a trace guaranteed to carry at least one log (the root
     * span's own PersonController.persons() error log - see LogsTabIT's
     * logsFilterChipUsesTheContrastTunedForeground, which relies on the same /?error=true
     * path), then switches to the Logs tab.
     */
    private void openLogsTabWithAtLeastOneLogRow() {
        openPageThatLogsAnError();
        toolbar.openOverlay();
        overlay.openLogsTab();
    }

    /**
     * Waits for the feedback on {@code control} and returns the result it should announce, the
     * text of every live region on the surface, how many regions sit inside a copy control and
     * how many sit in a dialog. One poll reads them all, so the control's reset cannot fall
     * between the reads.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> awaitCopyFeedback(ShadowHost surface, String control) {
        return (Map<String, Object>) surface.waitUntil("""
                (root, control) => {
                    const copy = root.querySelector(control);
                    const result = copy.classList.contains('pk-copy--copied') ? 'Copied'
                        : copy.classList.contains('pk-copy--failed') ? 'Copy failed' : null;
                    return result && {
                        result,
                        announced: [...root.querySelectorAll('.pk-copy__status[role=status]')]
                            .map(region => region.textContent),
                        regionsInAControl: root.querySelectorAll('.pk-copy [role=status]').length,
                        regionsInADialog: root.querySelectorAll('[role=dialog] > .pk-copy__status').length
                    };
                }
                """, control);
    }

    @Test
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

    // Reading the clipboard back needs the clipboard-read permission, which only Chromium has.
    @Tag("chromium-only")
    @Test
    void clickingTheTraceIdCopiesItWithoutOpeningTheOverlay() {
        openPageWithToolbar();
        String traceId = toolbar.traceId();

        toolbar.click("#pk-trace .pk-copy");

        toolbar.waitUntil("root => root.querySelector('#pk-trace .pk-copy').classList.contains('pk-copy--copied')");
        assertThat((String) page.evaluate("() => navigator.clipboard.readText()"))
                .as("the full id reaches the clipboard")
                .isEqualTo(traceId);
        assertThat(page.querySelector(TraceOverlay.HOST))
                .as("the whole toolbar bar opens the overlay on click; copying an id must not "
                        + "also trigger it, which needs the copy handler to run in the capture phase")
                .isNull();
    }

    @Test
    void copyControlIsNotNestedInsideTheOpenButton() {
        openPageWithToolbar();

        boolean nested = (boolean) toolbar.evaluate("root => !!root.querySelector('.pk-toolbar__open .pk-copy')");

        assertThat(nested)
                .as("a button inside a button is invalid, and ARIA treats a button's children as "
                        + "presentational - the copy control would be pruned from the accessibility tree")
                .isFalse();
    }

    @Test
    void copyControlHasAnAccessibleName() {
        openPageWithToolbar();

        String label = (String)
                toolbar.evaluate("root => root.querySelector('#pk-trace .pk-copy').getAttribute('aria-label')");
        String traceId = toolbar.traceId();

        assertThat(label).isEqualTo("Copy traceId " + traceId);
    }

    @Test
    void traceListRendersCopyableIds() {
        String traceId = openTracesTabWithATrace();

        assertThat(page.getAttribute(Dashboard.traceItem(traceId) + " .pk-copy", "data-pk-copy"))
                .as("the row shows a shortened id but copies the whole one")
                .isEqualTo(traceId);
    }

    /**
     * Every Logs tab row carries its span's full id next to the name button that filters to
     * it; the span tree keeps ids off its rows and in each span's details panel.
     */
    @Test
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

    /**
     * The span id in a span's attributes panel copies like every other id and leaves the
     * panel open.
     */
    @Test
    void clickingASpanIdInItsAttributesPanelCopiesItAndLeavesThePanelOpen() {
        openPageWithToolbar();
        toolbar.openOverlay();
        overlay.waitFor("#pk-gantt-rows .pk-gantt-name__toggle");

        overlay.click("#pk-gantt-rows .pk-gantt-name__toggle");
        overlay.click("#pk-gantt-rows .pk-span-panel--attrs .pk-copy");

        overlay.waitUntil("root => root.querySelector('#pk-gantt-rows .pk-span-panel--attrs .pk-copy')"
                + ".classList.contains('pk-copy--copied')");
        assertThat(
                        (Boolean)
                                overlay.evaluate(
                                        "root => root.querySelector('#pk-gantt-rows .pk-gantt-span').classList.contains('pk-gantt-span--open')"))
                .isTrue();
    }

    /**
     * The toolbar's usual home: an application served over plain HTTP, where
     * {@code navigator.clipboard} does not exist and the control falls back to the legacy
     * selection copy. Both outcomes are pinned - execCommand may or may not be honoured in a
     * headless run, and the point is that the reader is told which one happened rather than
     * left with a control that did nothing.
     */
    @Test
    void anInsecureContextFallsBackToTheLegacyCopyAndSaysHowItWent() {
        page.addInitScript("Object.defineProperty(window, 'isSecureContext', {get: () => false});");
        openPageWithToolbar();

        toolbar.click("#pk-trace .pk-copy");

        Map<String, Object> feedback = awaitCopyFeedback(toolbar, "#pk-trace .pk-copy");
        assertThat(feedback.get("announced")).isEqualTo(List.of(feedback.get("result")));
    }

    /**
     * A button's children are presentational, so a status inside one is never read out. The
     * result goes to the one live region of the surface the control sits on.
     */
    @Test
    void theCopyResultIsAnnouncedOutsideTheButton() {
        openPageWithToolbar();

        toolbar.click("#pk-trace .pk-copy");

        Map<String, Object> feedback = awaitCopyFeedback(toolbar, "#pk-trace .pk-copy");
        assertThat(feedback.get("regionsInAControl")).isEqualTo(0);
        assertThat(feedback.get("announced")).isEqualTo(List.of(feedback.get("result")));
    }

    /**
     * The overlay is a modal dialog, and what lies outside one is hidden from a screen reader
     * while it is open. Its live region is therefore a child of the dialog, one for all its ids.
     */
    @Test
    void aCopyInTheOverlayIsAnnouncedInsideItsDialog() {
        openLogsTabWithAtLeastOneLogRow();

        overlay.click(".pk-log__span-cell .pk-copy");

        Map<String, Object> feedback = awaitCopyFeedback(overlay, ".pk-log__span-cell .pk-copy");
        assertThat(feedback.get("regionsInAControl")).isEqualTo(0);
        assertThat(feedback.get("announced")).isEqualTo(List.of(feedback.get("result")));
        assertThat(feedback.get("regionsInADialog")).isEqualTo(1);
    }
}
