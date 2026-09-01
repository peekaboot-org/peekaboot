package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

class LifecycleEventRecorderTest {

    private static BuildProperties buildProperties() {
        Properties properties = new Properties();
        properties.setProperty("version", "1.2.3");
        properties.setProperty("time", "1756000000000");
        properties.setProperty("artifact", "orders");
        return new BuildProperties(properties);
    }

    private static GitProperties gitProperties() {
        Properties properties = new Properties();
        properties.setProperty("branch", "dev");
        properties.setProperty("commit.id", "abc1234def5678");
        return new GitProperties(properties);
    }

    private static LifecycleEventLog log() {
        LifecycleEventLog log = new LifecycleEventLog(null);
        log.beginLoad();
        return log;
    }

    @Test
    void aReadyApplicationIsRecordedWithEveryBuildAndGitProperty() {
        LifecycleEventLog log = log();
        LifecycleEventRecorder recorder = new LifecycleEventRecorder(
                log, new BuildInfoProvider(buildProperties()), gitProperties(), mock(ApplicationContext.class));
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        when(event.getTimestamp()).thenReturn(1_756_000_000_000L);

        recorder.onReady(event);

        // onReady defers to recordWhenLoaded, which never blocks its caller.
        await().atMost(Duration.ofSeconds(5)).until(() -> log.events().size() == 1);
        LifecycleEvent recorded = log.events().get(0);
        assertThat(recorded.type()).isEqualTo(LifecycleEvent.Type.START);
        assertThat(recorded.epochMs()).isEqualTo(1_756_000_000_000L);
        assertThat(recorded.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(recorded.build()).containsEntry("version", "1.2.3").containsEntry("artifact", "orders");
        assertThat(recorded.git()).containsEntry("branch", "dev").containsEntry("commit.id", "abc1234def5678");
    }

    @Test
    void anApplicationWithoutBuildOrGitInfoStillRecordsItsStart() {
        LifecycleEventLog log = log();
        LifecycleEventRecorder recorder =
                new LifecycleEventRecorder(log, new BuildInfoProvider(null), null, mock(ApplicationContext.class));
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        when(event.getTimestamp()).thenReturn(1L);

        recorder.onReady(event);

        await().atMost(Duration.ofSeconds(5)).until(() -> log.events().size() == 1);
        assertThat(log.events().get(0).build()).isEmpty();
        assertThat(log.events().get(0).git()).isEmpty();
    }

    @Test
    void aClosingContextIsRecordedAsAStop() {
        LifecycleEventLog log = log();
        ApplicationContext context = mock(ApplicationContext.class);
        LifecycleEventRecorder recorder = new LifecycleEventRecorder(log, new BuildInfoProvider(null), null, context);

        recorder.onClosed(closing(context));

        assertThat(log.events()).extracting(LifecycleEvent::type).containsExactly(LifecycleEvent.Type.STOP);
    }

    /**
     * With a separate management port, Boot closes the child management context as the
     * parent closes and forwards its ContextClosedEvent to the parent; recording that too
     * would put two stops in the log for one shutdown.
     */
    @Test
    void anotherContextsCloseIsNotRecorded() {
        LifecycleEventLog log = log();
        LifecycleEventRecorder recorder =
                new LifecycleEventRecorder(log, new BuildInfoProvider(null), null, mock(ApplicationContext.class));

        recorder.onClosed(closing(mock(ApplicationContext.class)));

        assertThat(log.events()).isEmpty();
    }

    private static ContextClosedEvent closing(ApplicationContext context) {
        ContextClosedEvent event = mock(ContextClosedEvent.class);
        when(event.getApplicationContext()).thenReturn(context);
        return event;
    }
}
