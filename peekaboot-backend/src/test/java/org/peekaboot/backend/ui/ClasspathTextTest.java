package org.peekaboot.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;

class ClasspathTextTest {

    @Test
    void readsAFileTheFrontendJarServes() {
        assertThat(ClasspathText.read("/ui/assets/tokens.css"))
                .hasValueSatisfying(css -> assertThat(css).contains("--pk-bg:"));
    }

    @Test
    void isEmptyAndWarnsForAFileThatIsNotThere() {
        try (LogCapture capture = LogCapture.attach(ClasspathText.class)) {
            assertThat(ClasspathText.read("/ui/assets/nope.css")).isEmpty();

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo("/ui/assets/nope.css not found on the classpath");
            });
        }
    }
}
