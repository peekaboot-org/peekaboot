package org.peekaboot.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Logs the security posture below the ApplicationReady banner. */
@Order(Ordered.LOWEST_PRECEDENCE)
public class SecurityPostureListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SecurityPostureListener.class);

    private final SecurityPosture posture;

    public SecurityPostureListener(SecurityPosture posture) {
        this.posture = posture;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        posture.report().ifPresent(report -> {
            if (report.level() == SecurityPosture.Level.WARN) {
                log.warn(report.text());
            } else {
                log.info(report.text());
            }
        });
    }
}
