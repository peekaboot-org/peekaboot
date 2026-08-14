package org.peekaboot.example;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
public class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    @Autowired
    private ExampleService exampleService;


    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void fixedRate() {

        log.info("fixedRate start");
        exampleService.getPerson(1);

        log.error("fixedRate failed");
    }


    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void fixedDelay() {

        log.info("fixedDelay start");
        throw new IllegalStateException("fixedDelay failed");
    }


    @Scheduled(cron = "0 0 * * * *")
    public void cron1() {

    }


    @Scheduled(cron = "0 0/30 9-17 * * MON-FRI")
    public void cron2() {

    }


    @Scheduled(cron = "0 15 10 ? * 6#3")
    public void cron3() {

    }
}