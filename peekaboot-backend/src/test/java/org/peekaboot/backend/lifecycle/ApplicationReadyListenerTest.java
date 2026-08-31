package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.LogCapture;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.mock.env.MockEnvironment;

class ApplicationReadyListenerTest {

    @Test
    void banner_linksTheDashboardBelowTheServiceUrlAndSwaggerUi() {
        String report = report(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(report)
                .containsSubsequence(" Service URL:", " Swagger UI:", " Peekaboot Dashboard:")
                .contains(" Peekaboot Dashboard: http://localhost:8083/peekaboot/");
    }

    @Test
    void banner_omitsTheDashboardWhenPeekabootDoesNotServeIt() {
        String report = report(ReadyEvents.webApplication(8083));

        assertThat(report).contains(" Service URL:").doesNotContain("Peekaboot Dashboard");
    }

    /** Runs the listener against {@code event} and returns the single banner it logs. */
    private static String report(ApplicationReadyEvent event) {
        var listener = new ApplicationReadyListener(
                new EnvironmentInfo(new MockEnvironment()),
                new BuildInfoProvider(null),
                new ServerUrlResolver(new MockEnvironment(), () -> true),
                List.of(),
                Map.of());

        try (var capture = LogCapture.attach(ApplicationReadyListener.class)) {
            listener.onApplicationEvent(event);

            assertThat(capture.appender().list).hasSize(1);
            return capture.appender().list.get(0).getFormattedMessage();
        }
    }
}
