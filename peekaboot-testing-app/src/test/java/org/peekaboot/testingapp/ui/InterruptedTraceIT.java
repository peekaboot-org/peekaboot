package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.nio.file.Path;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A trace an earlier run left without its root. {@link TraceJournalFixture} writes it into a storage
 * directory of this class's own before the context boots; the context's trace journal restores it,
 * and the overlay header calls it interrupted, never in progress.
 *
 * <p>Closed with the class: storage is on, so the journal and the insights snapshot keep writing into
 * {@code storageDir} until the context closes, and a cached context would outlive the directory (see
 * {@code LifecycleTabIT}).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterruptedTraceIT extends TraceOverlayTestBase {

    private static final String TRACE_ID = "1d1e5e1f00000000000000000000beef";
    private static final String UNLISTED_TRACE_ID = "1d1e5e1f00000000000000000000dead";
    private static final String INTERRUPTED = "trace => trace.summary.outcome === 'INTERRUPTED'";

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void seededJournal(DynamicPropertyRegistry registry) {
        TraceJournalFixture.writeInterruptedTrace(storageDir, TRACE_ID, UNLISTED_TRACE_ID);
        registry.add("peekaboot.storage.enabled", () -> "true");
        registry.add("peekaboot.storage.dir", storageDir::toString);
    }

    @Test
    void anInterruptedTraceSaysSoAndShowsTheSpansThatArrived() {
        awaitTrace(TRACE_ID, INTERRUPTED);

        openTraceDeepLink(TRACE_ID, "/spans");

        overlay.waitFor(".pk-gantt-row[data-span-id='query']");
        assertThat(
                        overlay.evaluate(
                                "root => [...root.querySelectorAll('.pk-overlay__meta .pk-badge')].map(badge => badge.textContent)"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .as("the status badge, then the completion badge")
                .containsExactly("-", "INTERRUPTED");
        assertThat(overlay.evaluate("""
                        root => {
                            const badge = root.querySelectorAll('.pk-overlay__meta .pk-badge')[1];
                            return {className: badge.className, title: badge.title};
                        }
                        """))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsOnly(
                        entry("className", "pk-badge pk-badge--muted"),
                        entry(
                                "title",
                                "The application stopped before the root span arrived, so this trace will never"
                                        + " complete. This shows the spans that did arrive."));
    }

    @Test
    void theRestoredTraceNamesTheRunThatProducedIt() {
        assertThat(awaitTrace(TRACE_ID, INTERRUPTED).path("summary").path("run").asString(""))
                .isEqualTo(TraceJournalFixture.EARLIER_RUN);
    }

    @Test
    void aTraceNoBucketLineListsIsNotRestored() {
        // INTERRUPTED shows once the live run begins, which the restore starts after the bucket lines
        awaitTrace(TRACE_ID, INTERRUPTED);

        assertThat(page.request()
                        .get(baseUrl + contextPath() + "/peekaboot/api/traces/" + UNLISTED_TRACE_ID)
                        .status())
                .isEqualTo(404);
    }
}
