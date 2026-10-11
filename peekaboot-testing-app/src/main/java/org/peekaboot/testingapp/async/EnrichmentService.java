package org.peekaboot.testingapp.async;

import org.peekaboot.testingapp.SimulatedWork;
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
 * uncaught-exception handler rather than into a future nobody reads. Peekaboot still fails
 * that run's {@code async task} span.
 */
@Service
public class EnrichmentService {

    private static final long WORK_MILLIS = 150;

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    private final CustomerNotifier customerNotifier;

    public EnrichmentService(CustomerNotifier customerNotifier) {

        this.customerNotifier = customerNotifier;
    }

    @Async
    public void enrich(String reference, boolean fail) {

        enrichNow(reference, fail);
    }

    /** Hands the notification to another task, which then outlives this one. */
    @Async
    public void enrichAndNotify(String reference) {

        enrichNow(reference, false);
        customerNotifier.notifyEnriched(reference);
    }

    private void enrichNow(String reference, boolean fail) {

        log.info("enriching order {}", reference);
        SimulatedWork.pause(WORK_MILLIS);
        if (fail) {
            throw new IllegalStateException("enrichment of " + reference + " was rejected");
        }
        log.info("enriched order {}", reference);
    }
}
