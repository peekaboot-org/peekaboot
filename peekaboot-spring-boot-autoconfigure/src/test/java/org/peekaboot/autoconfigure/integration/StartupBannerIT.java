package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.lifecycle.ApplicationReadyListener;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots a real application to prove the startup banner links the dashboard.
 *
 * <p>ServerUrlResolver finds the dashboard's configuration by bean name rather than by type,
 * so only a fully component-scanned context can show that the name it looks for is the name
 * Spring actually registers.
 */
class StartupBannerIT {

    @Test
    void bannerLinksTheDashboardOfARunningApplication() {
        try (BannerCapture bannerCapture = new BannerCapture();
                ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                        .profiles("integration")
                        .properties("server.port=0")
                        .listeners(bannerCapture)
                        .run()) {

            int port = ((WebServerApplicationContext) context).getWebServer().getPort();

            assertThat(bannerCapture.banners()).hasSize(1);
            assertThat(bannerCapture.banners().get(0).getFormattedMessage())
                    .containsSubsequence(" Service URL:", " Peekaboot Dashboard:")
                    .contains(" Peekaboot Dashboard: http://localhost:" + port + "/peekaboot/");
        }
    }

    /**
     * Captures the banner. Attaches on {@link ApplicationStartedEvent} rather than before the
     * application starts, because Spring Boot initializes the logging system during startup and
     * that discards any appender added beforehand. The banner follows on ApplicationReadyEvent.
     */
    private static final class BannerCapture implements ApplicationListener<ApplicationStartedEvent>, AutoCloseable {

        private LogCapture capture;

        @Override
        public void onApplicationEvent(ApplicationStartedEvent event) {
            capture = LogCapture.attach(ApplicationReadyListener.class);
        }

        List<ILoggingEvent> banners() {
            return capture.appender().list;
        }

        @Override
        public void close() {
            if (capture != null) {
                capture.close();
            }
        }
    }
}
