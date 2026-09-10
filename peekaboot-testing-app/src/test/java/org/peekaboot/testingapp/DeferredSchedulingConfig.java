package org.peekaboot.testingapp;

import io.micrometer.observation.ObservationHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.peekaboot.testingapp.integration.ScheduledJobs;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Keeps the sample app's demo jobs from going off on their own during a test run. They exist
 * to give the dashboard something to render, and {@link Scheduler#fixedDelay()} throws on
 * every invocation - left to their timers they only bury the build log in deliberate stack
 * traces. Every test that wants a job's trace runs that job itself through
 * {@code ScheduledJobs}.
 *
 * <p>It lives in test sources under the application's own package and carries a plain
 * {@code @Configuration}, so {@link TestingApp}'s component scan finds it for every
 * {@code @SpringBootTest} without any of them having to name it. Naming the two profiles the
 * IT suite boots under keeps it out of the app when the app is run for real - and out of the
 * screenshot run, whose whole point is a dashboard photographed with lifelike data.
 */
@Configuration
@Profile({"test", "security"})
public class DeferredSchedulingConfig {

    @Bean
    TaskScheduler taskScheduler() {
        return new DeferredTaskScheduler();
    }

    /**
     * Lets {@code ScheduledJobs.run} answer with the trace id of the run it fired, so a test
     * waits for its own job trace rather than for whichever one names that job.
     */
    @Bean
    ObservationHandler<ScheduledTaskObservationContext> scheduledJobTraceIdRecorder() {
        return ScheduledJobs.traceIdRecorder();
    }

    /**
     * Accepts every task the real scheduler would - {@code ScheduledTaskHolder} still lists
     * them, so the Scheduled Tasks tab and {@code ScheduledJobs} keep working - and gives each
     * one a start time no build lives to see.
     */
    private static final class DeferredTaskScheduler implements TaskScheduler, DisposableBean {

        private static final long BEYOND_ANY_BUILD_MS = Duration.ofDays(1).toMillis();

        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "deferred-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return defer(task);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return defer(task);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return defer(task);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return defer(task);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return defer(task);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return defer(task);
        }

        @Override
        public void destroy() {
            executor.shutdownNow();
        }

        private ScheduledFuture<?> defer(Runnable task) {
            return executor.schedule(task, BEYOND_ANY_BUILD_MS, TimeUnit.MILLISECONDS);
        }
    }
}
