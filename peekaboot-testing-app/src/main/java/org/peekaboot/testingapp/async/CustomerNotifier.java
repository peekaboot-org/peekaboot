package org.peekaboot.testingapp.async;

import org.peekaboot.testingapp.SimulatedWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Tells the customer an order was enriched, on a task executor of its own. Called from inside
 * {@link EnrichmentService}'s task, so the dashboard has background work started by background
 * work, and it runs longer than the task that started it.
 */
@Service
public class CustomerNotifier {

    private static final long WORK_MILLIS = 300;

    private static final Logger log = LoggerFactory.getLogger(CustomerNotifier.class);

    @Async
    public void notifyEnriched(String reference) {

        log.info("notifying the customer about order {}", reference);
        SimulatedWork.pause(WORK_MILLIS);
        log.info("notified the customer about order {}", reference);
    }
}
