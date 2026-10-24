package org.peekaboot.testingapp.ui;

import java.util.Map;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A Playwright test of the trace-detail overlay, with the ways in and the reads that more than one
 * of the overlay's test classes needs.
 */
abstract class TraceOverlayTestBase extends PlaywrightTestBase {

    @Autowired
    protected TraceStore traceStore;

    protected void openOverlayFromToolbar() {
        openPersonsPage();
        toolbar.openOverlay();
    }

    /**
     * Waits until the trace the toolbar currently tracks carries logs on more than one span,
     * and returns its id: the real, non-vacuous precondition
     * {@link SpansTabIT#spanLogsToggleOpensThatSpansOwnLogsInPlace} needs.
     * It counts spans carrying logs the way spans.js does, by span id over the trace's flat log
     * list, so the precondition is measured against the very shape the Spans tab renders its
     * "N logs" toggles from.
     */
    private String waitForMultiSpanLogTraceId() {
        String traceId = toolbar.traceId();
        awaitTrace(traceId, "trace => new Set((trace.logs || []).map(log => log.spanId).filter(Boolean)).size > 1");
        return traceId;
    }

    /**
     * Opens the overlay for the index page's error-path trace - the one trace this app
     * produces whose logs are spread over more than one span: the request handler's own
     * ERROR log, and the INFO line PersonQueryService.findAll() writes inside its own
     * observed span. Returns that trace's id.
     *
     * <p>It opens through the dashboard's hash route rather than the toolbar, because
     * only that path supplies an urlState (main.js's expandTraceById -> buildTraceUrlState).
     * The toolbar calls openTraceDetail with none, so there every urlState write is a silent
     * no-op and the URL assertion in {@link SpansTabIT#spanLogsToggleOpensThatSpansOwnLogsInPlace}
     * could not fail even if the wiring were deleted.
     */
    protected String openOverlayForTheMultiSpanLogTrace() {
        openPageThatLogsAnError();

        String traceId = waitForMultiSpanLogTraceId();

        openTraceDeepLink(traceId);
        overlay.waitFor("#pk-gantt-rows");
        return traceId;
    }

    protected void openOverlayFromTheDashboard(String traceId) {
        openTraceDeepLink(traceId);
        overlay.waitFor(".pk-overlay__meta");
    }

    /**
     * The row a span jump flashed, the row holding focus and whether the flashed row shows, read
     * in one poll so the flash's 2s timer cannot end between the reads.
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> awaitJumpLanding() {
        return (Map<String, Object>) overlay.waitUntil("""
                root => {
                    const row = root.querySelector('.pk-gantt-row.pk-jump-flash');
                    return row && {
                        flashed: row.dataset.spanId,
                        focused: root.activeElement?.closest('.pk-gantt-row')?.dataset.spanId ?? null,
                        visible: row.checkVisibility()
                    };
                }
                """);
    }
}
