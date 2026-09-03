package org.peekaboot.testingapp;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Demo signals for the dashboard's Errors bucket and the trace overlay's log filters:
 * {@link #fixedRate()} logs an ERROR without failing (a trace with INFO and ERROR rows on
 * one span), {@link #fixedDelay()} throws (a scheduled-job trace with an exception). Both
 * are asserted on in the UI suite; the cron methods only give the Scheduled Tasks tab
 * schedules to render.
 */
@Component
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final PersonQueryService personQueryService;

    public Scheduler(PersonQueryService personQueryService) {
        this.personQueryService = personQueryService;
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void fixedRate() {

        log.info("fixedRate start");
        personQueryService.getPerson(1);

        log.error("fixedRate failed");
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void fixedDelay() {

        log.info("fixedDelay start");
        throw new IllegalStateException("fixedDelay failed");
    }

    @Scheduled(cron = "0 0 * * * *")
    public void cron1() {
        // intentionally empty - exists so the dashboard has a cron schedule to render
    }

    @Scheduled(cron = "0 0/30 9-17 * * MON-FRI")
    public void cron2() {
        // intentionally empty - exists so the dashboard has a cron schedule to render
    }

    @Scheduled(cron = "0 15 10 ? * 6#3")
    public void cron3() {
        // intentionally empty - exists so the dashboard has a cron schedule to render
    }
}
