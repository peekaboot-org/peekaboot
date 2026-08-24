package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.integration.LateSpanFixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The collapsed bar used to poll with backoff and stop the moment a trace looked complete, so
 * anything ending after the response - an {@code @Async} continuation, a streamed body - never
 * appeared on it. Four fixed attempts out to 4.75s close that gap.
 *
 * <p>Real app, real spans, real browser: {@link LateSpanFixture} ends a genuine child span ~800ms
 * after the response, and the assertions below check that it really is the same trace and really
 * did end after the response, rather than trusting the tracing wiring.
 *
 * <p>That 800ms is not incidental: it has to outlast the toolbar's first render, or the "duration
 * changes" assertion below has nothing to change to and times out. See {@link
 * LateSpanFixture.LateSpanController#LATE_WORK} for the exact margin against the fetch ladder and
 * the test export delay.
 */
@Import(LateSpanFixture.class)
class ToolbarLateSpanTest extends PlaywrightTestBase {

    /**
     * The bar exposes no span count, and adding one purely to make this observable would be
     * production code written for a test. The rendered {@code ⏱} duration moves instead: the
     * trace's duration spans its earliest start to its latest end, so a child ending 800ms after
     * the root stretches it from a handful of milliseconds to at least {@code LATE_WORK}. The
     * query and log counters cannot serve - this request issues neither.
     */
    private static final String RENDERED_DURATION = "() => { const el ="
            + " document.getElementById('peekaboot-toolbar-host')"
            + ".shadowRoot.querySelector('#pk-metrics .pk-stat__duration');"
            + " return el ? el.textContent : null; }";

    /** Same value, but only once it differs from the first render - i.e. once the bar re-read. */
    private static final String RENDERED_DURATION_OTHER_THAN = "first => { const el ="
            + " document.getElementById('peekaboot-toolbar-host')"
            + ".shadowRoot.querySelector('#pk-metrics .pk-stat__duration');"
            + " return el && el.textContent !== first ? el.textContent : null; }";

    private static final String RENDERED_TRACE_ID = "() => document"
            + ".getElementById('peekaboot-toolbar-host')"
            + ".shadowRoot.querySelector('#pk-trace .pk-copy').dataset.pkCopy";

    @Autowired
    private LateSpanFixture.LateSpanController lateSpanController;

    @Test
    void collapsedBarPicksUpASpanThatEndsAfterTheResponse() throws InterruptedException {
        page.navigate(baseUrl + "/late-span");
        Instant responseReceivedAt = Instant.now();
        page.waitForSelector("#peekaboot-toolbar-host");

        // The render the old backoff loop stopped at: the root span has been exported, the
        // late child is still running.
        String firstRender = (String)
                page.waitForFunction(RENDERED_DURATION, null, within(10_000)).jsonValue();
        String barTraceId = (String) page.evaluate(RENDERED_TRACE_ID);

        LateSpanFixture.LateSpan lateSpan = lateSpanController.awaitLateSpan(Duration.ofSeconds(10));
        assertThat(lateSpan.traceId())
                .as("the late span must belong to the trace the bar is reading")
                .isEqualTo(barTraceId);
        assertThat(lateSpan.endedAt())
                .as("the late span must end after the response reached the browser")
                .isAfter(responseReceivedAt);

        String laterRender = (String) page.waitForFunction(RENDERED_DURATION_OTHER_THAN, firstRender, within(15_000))
                .jsonValue();

        assertThat(renderedDurationMs(laterRender))
                .isGreaterThan(renderedDurationMs(firstRender))
                .isGreaterThanOrEqualTo((double) LateSpanFixture.LateSpanController.LATE_WORK.toMillis());
    }

    private static Page.WaitForFunctionOptions within(int timeoutMs) {
        return new Page.WaitForFunctionOptions().setTimeout(timeoutMs);
    }

    /**
     * Reverses formatDurationMs, which renders "&lt;1ms", "812ms", "1.02s" or "1.50m" - comparing
     * two renders needs the magnitude back, not the label.
     */
    private static double renderedDurationMs(String rendered) {
        if ("<1ms".equals(rendered)) {
            return 0.5;
        }
        if (rendered.endsWith("ms")) {
            return Double.parseDouble(rendered.substring(0, rendered.length() - 2));
        }
        if (rendered.endsWith("s")) {
            return Double.parseDouble(rendered.substring(0, rendered.length() - 1)) * 1_000;
        }
        if (rendered.endsWith("m")) {
            return Double.parseDouble(rendered.substring(0, rendered.length() - 1)) * 60_000;
        }
        throw new AssertionError("unrecognised duration rendering: " + rendered);
    }
}
