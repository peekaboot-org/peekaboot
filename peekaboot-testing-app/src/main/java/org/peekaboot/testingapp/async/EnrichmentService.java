package org.peekaboot.testingapp.async;

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
 * uncaught-exception handler rather than into a future nobody reads.
 */
@Service
public class EnrichmentService {

    private static final long WORK_MILLIS = 150;

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    @Async
    public void enrich(String reference) {

        log.info("enriching order {}", reference);
        pause(WORK_MILLIS);
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
