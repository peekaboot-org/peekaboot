package org.peekaboot.testingapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class TestingApp {

    public static void main(String[] args) {

        SpringApplication.run(TestingApp.class, args);
    }
}
