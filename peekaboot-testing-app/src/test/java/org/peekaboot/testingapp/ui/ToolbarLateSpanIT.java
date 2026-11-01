package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.fixtures.LateSpanController;
import org.peekaboot.testingapp.integration.TestSpans;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The collapsed bar polls in four fixed attempts out to 4.75s rather than stopping the moment a
 * trace looks complete, so work ending after the response - an {@code @Async} continuation, a
 * streamed body - still appears on it.
 *
 * <p>Real app, real spans, real browser: {@link LateSpanController} keeps a genuine child span
 * running past the response, and the assertions below check that it really is the same trace,
 * rather than trusting the tracing wiring.
 *
 * <p>The test owns the order. It holds the bar's first read until the root span is stored, so
 * that read renders the short baseline, and only then releases the late span. Anything left on
 * a clock would race the page load, which stretches without bound on a busy machine.
 */
class ToolbarLateSpanIT extends PlaywrightTestBase {

    /**
     * The rendered {@code ⏱} duration is the signal, not the span count: the trace's duration
     * spans its earliest start to its latest end, so the late child stretches it from a
     * handful of milliseconds to at least {@code LATE_WORK}, while the span count only steps by
     * one and could land on either render depending on when the backend counts a still-running
     * child. The query and log counters cannot serve at all - this request issues neither.
     */
    private static final String RENDERED_DURATION =
            "root => root.querySelector('#pk-metrics .pk-stat__duration')?.textContent ?? null";

    /** Same value, but only once it differs from the first render - i.e. once the bar re-read. */
    private static final String RENDERED_DURATION_OTHER_THAN = "(root, first) => { const el ="
            + " root.querySelector('#pk-metrics .pk-stat__duration');"
            + " return el && el.textContent !== first ? el.textContent : null; }";

    @Autowired
    private LateSpanController lateSpanController;

    @Autowired
    private TraceStore traceStore;

    @Autowired
    private PeekabootTracingProperties tracingProperties;

    @Test
    void collapsedBarPicksUpASpanThatEndsAfterTheResponse() throws InterruptedException {
        ParkedRequest firstRead = parkFirst("**/api/traces/*");
        page.navigate(baseUrl + "/late-span");
        String barTraceId = toolbar.traceId();

        firstRead.awaitParked();
        awaitTrace(barTraceId, ROOT_SPAN_EXPORTED);
        firstRead.resume();
        String firstRender = (String) toolbar.waitUntil(RENDERED_DURATION, null, 10_000);

        lateSpanController.release();
        String laterRender = (String) toolbar.waitUntil(RENDERED_DURATION_OTHER_THAN, firstRender, 15_000);

        assertThat(lateSpanController.awaitLateSpanTraceId(Duration.ofSeconds(10)))
                .as("the late span must belong to the trace the bar is reading")
                .isEqualTo(barTraceId);
        assertThat(renderedDurationMs(laterRender))
                .isGreaterThan(renderedDurationMs(firstRender))
                .isGreaterThanOrEqualTo((double) LateSpanController.LATE_WORK.toMillis());
    }

    /**
     * Spans ending after the response can push the root out past the span cap. The request
     * still ended, so the trace stays complete with a null root, and the bar renders what is
     * left rather than reading it as a trace that never arrived. Written straight to the
     * store: no endpoint issues five hundred late spans.
     */
    @Test
    void collapsedBarRendersACompleteTraceWhoseRootLateSpansEvicted() {
        ParkedRequest firstRead = parkFirst("**/api/traces/*");
        openPersonsPage();
        String barTraceId = toolbar.traceId();

        firstRead.awaitParked();
        JsonNode stored = awaitTrace(barTraceId, ROOT_SPAN_EXPORTED);
        String rootSpanId = stored.path("data").path("rootSpan").path("spanId").asString();
        Instant rootStart = Instant.parse(
                stored.path("data").path("rootSpan").path("startTime").asString());
        int cap = tracingProperties.getMaxSpansPerTrace();
        for (int i = 0; i < cap; i++) {
            traceStore.addSpan(TestSpans.span(barTraceId, String.format("late%012x", i))
                    .parent(rootSpanId)
                    .named("late work")
                    .at(rootStart.toEpochMilli() + 1, 1)
                    .build());
        }
        awaitTrace(barTraceId, "trace => trace.summary.complete && trace.data.rootSpan === null");
        firstRead.resume();

        // a rendered stat line or the pending one, whichever the bar settles on first
        toolbar.waitUntil("root => root.querySelector('#pk-metrics .pk-stat') !== null", null, 10_000);

        assertThat(toolbar.text("#pk-metrics")).contains(cap + " spans");
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
