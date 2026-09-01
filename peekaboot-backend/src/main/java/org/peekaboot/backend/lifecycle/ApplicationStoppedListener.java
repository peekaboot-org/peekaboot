package org.peekaboot.backend.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Logs what the ready banner's reader will look for on the way out: how long the
 * application ran, and between which two points.
 *
 * <p>Uptime is measured from the context's own start date rather than from the ready
 * event, so the banner still holds when an application is shut down before it ever
 * became ready. That is a few seconds earlier than readiness, which is why the line
 * says which start it means.
 */
public class ApplicationStoppedListener implements ApplicationListener<ContextClosedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationStoppedListener.class);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final BuildInfoProvider buildInfoProvider;

    public ApplicationStoppedListener(BuildInfoProvider buildInfoProvider) {
        this.buildInfoProvider = buildInfoProvider;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {

        Instant started = Instant.ofEpochMilli(event.getApplicationContext().getStartupDate());
        Instant stopped = Instant.now();

        StringBuilder report = LifecycleBanner.open("ApplicationStopped");
        report.append(String.format(
                        " Application [%s] stopped after %s",
                        buildInfoProvider.getName(), UptimeFormat.humanize(Duration.between(started, stopped))))
                .append("\n");
        report.append(LifecycleBanner.LINE).append("\n");
        report.append(" Up since (context start): ")
                .append(TIMESTAMP.format(started))
                .append("\n");
        report.append(" Stopped:                  ")
                .append(TIMESTAMP.format(stopped))
                .append("\n");
        LifecycleBanner.close(report);

        logger.info(report.toString());
    }
}
