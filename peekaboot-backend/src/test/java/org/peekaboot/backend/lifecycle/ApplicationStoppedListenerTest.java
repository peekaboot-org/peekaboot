package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

class ApplicationStoppedListenerTest {

    private static ApplicationContext contextUpFor(Duration uptime) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getStartupDate()).thenReturn(System.currentTimeMillis() - uptime.toMillis());
        return context;
    }

    private static ContextClosedEvent closing(ApplicationContext context) {
        ContextClosedEvent event = mock(ContextClosedEvent.class);
        when(event.getApplicationContext()).thenReturn(context);
        return event;
    }

    private static String banner(Duration uptime, BuildInfoProvider buildInfo) {
        ApplicationContext context = contextUpFor(uptime);
        ApplicationStoppedListener listener = new ApplicationStoppedListener(buildInfo, context);
        try (LogCapture capture = LogCapture.attach(ApplicationStoppedListener.class)) {
            listener.onApplicationEvent(closing(context));

            assertThat(capture.appender().list).hasSize(1);
            return capture.appender().list.get(0).getFormattedMessage();
        }
    }

    /**
     * With a separate management port, Boot closes the child management context as the
     * parent closes and its ContextClosedEvent is forwarded to the parent's listeners; only
     * the context this listener belongs to gets a banner, or the application logs two.
     */
    @Test
    void anotherContextsCloseIsNotThisApplicationsStop() {
        ApplicationStoppedListener listener =
                new ApplicationStoppedListener(new BuildInfoProvider(null), contextUpFor(Duration.ofMinutes(5)));
        try (LogCapture capture = LogCapture.attach(ApplicationStoppedListener.class)) {
            listener.onApplicationEvent(closing(contextUpFor(Duration.ofMinutes(4))));

            assertThat(capture.appender().list).isEmpty();
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
