package org.peekaboot.testingapp.async;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Enriches a placed order on a task executor instead of on the request thread, so the
 * dashboard has a real thread hand-off to show and Peekaboot's async instrumentation real
 * work to capture.
 *
 * <p>Returns nothing on purpose: the endpoint that dispatches this answers while the task is
 * still running, and a {@code void} {@code @Async} method routes a failure to Spring's
 * uncaught-exception handler rather than into a future nobody reads. Observed as well,
 * because that exception never reaches the executor's own span; the observed span is the one
 * a failed run can fail.
 */
@Service
public class EnrichmentService {

    private static final long WORK_MILLIS = 150;

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    @Async
    @Observed(name = "order.enrichment", contextualName = "order.enrichment")
    public void enrich(String reference, boolean fail) {

        log.info("enriching order {}", reference);
        pause(WORK_MILLIS);
        if (fail) {
            throw new IllegalStateException("enrichment of " + reference + " was rejected");
        }
        log.info("enriched order {}", reference);
    }

    /** Stands in for work this demo does not actually do. */
    private void pause(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while simulating work", e);
        }
    }
}
