package net.osslabz.example;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
public class Scheduler {

    @Autowired
    private ExampleService exampleService;


    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void fixedRate() {

        exampleService.getPerson(1);
    }


    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.SECONDS)
    public void fixedDelay() {

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