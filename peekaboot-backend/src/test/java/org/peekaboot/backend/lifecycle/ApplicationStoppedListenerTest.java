package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.LogCapture;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

class ApplicationStoppedListenerTest {

    /** A close event whose context has been up for {@code uptime}. */
    private static ContextClosedEvent closedAfter(Duration uptime) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getStartupDate()).thenReturn(System.currentTimeMillis() - uptime.toMillis());
        ContextClosedEvent event = mock(ContextClosedEvent.class);
        when(event.getApplicationContext()).thenReturn(context);
        return event;
    }

    private static String banner(Duration uptime, BuildInfoProvider buildInfo) {
        ApplicationStoppedListener listener = new ApplicationStoppedListener(buildInfo);
        try (LogCapture capture = LogCapture.attach(ApplicationStoppedListener.class)) {
            listener.onApplicationEvent(closedAfter(uptime));

            assertThat(capture.appender().list).hasSize(1);
            return capture.appender().list.get(0).getFormattedMessage();
        }
    }

    @Test
    void theBannerNamesTheApplicationAndHowLongItRan() {
        String report = banner(Duration.ofDays(1).plusHours(2).plusMinutes(3), new BuildInfoProvider(null));

        assertThat(report).contains(":: ApplicationStopped ::").contains("stopped after 1 day, 2 hours, 3 minutes");
    }

    @Test
    void theBannerSaysWhichStartItMeasuresFrom() {
        String report = banner(Duration.ofMinutes(5), new BuildInfoProvider(null));

        assertThat(report).containsSubsequence("Up since (context start):", "Stopped:");
    }
}
