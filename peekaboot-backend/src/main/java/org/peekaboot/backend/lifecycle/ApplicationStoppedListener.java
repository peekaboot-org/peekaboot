package org.peekaboot.backend.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.peekaboot.backend.config.ApplicationIdentity;
import org.peekaboot.backend.text.BannerFrame;
import org.peekaboot.backend.text.UptimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
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

    private static final Logger log = LoggerFactory.getLogger(ApplicationStoppedListener.class);

    private final ApplicationIdentity applicationIdentity;
    private final ApplicationContext ownContext;
    private final Clock clock;
    private final DateTimeFormatter timestamp;

    /** The server's own zone is what the banner is read in, so the default zone is the right one here. */
    public ApplicationStoppedListener(ApplicationIdentity applicationIdentity, ApplicationContext ownContext) {
        this(applicationIdentity, ownContext, Clock.system(ZoneId.systemDefault()));
    }

    /** {@code clock} supplies the stop instant and the zone both timestamps are rendered in; tests fix it. */
    ApplicationStoppedListener(ApplicationIdentity applicationIdentity, ApplicationContext ownContext, Clock clock) {
        this.applicationIdentity = applicationIdentity;
        this.ownContext = ownContext;
        this.clock = clock;
        this.timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(clock.getZone());
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!ContextEvents.fromOwnContext(event, ownContext)) {
            return;
        }

        Instant started = Instant.ofEpochMilli(ownContext.getStartupDate());
        Instant stopped = clock.instant();

        StringBuilder report = BannerFrame.open("ApplicationStopped");
        BannerFrame.line(
                report,
                String.format(
                        " Application [%s] stopped after %s",
                        applicationIdentity.displayName(), UptimeFormat.humanize(Duration.between(started, stopped))));
        BannerFrame.text(report, " Up since (context start): " + timestamp.format(started));
        BannerFrame.text(report, " Stopped:                  " + timestamp.format(stopped));
        BannerFrame.close(report);

        log.info(report.toString());
    }
}
