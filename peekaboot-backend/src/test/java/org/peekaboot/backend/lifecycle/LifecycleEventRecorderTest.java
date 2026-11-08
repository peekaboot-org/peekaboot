package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.io.DefaultResourceLoader;

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

    private static ApplicationReadyEvent ready(ConfigurableApplicationContext context, long timestamp) {
        SpringApplication application = new SpringApplication(LifecycleEventRecorderTest.class);
        application.setMainApplicationClass(LifecycleEventRecorderTest.class);
        return ready(context, timestamp, application);
    }

    private static ApplicationReadyEvent ready(
            ConfigurableApplicationContext context, long timestamp, SpringApplication application) {
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        when(event.getApplicationContext()).thenReturn(context);
        when(event.getTimestamp()).thenReturn(timestamp);
        when(event.getSpringApplication()).thenReturn(application);
        return event;
    }

    @Test
    void aReadyApplicationIsRecordedWithTheBuildAndGitFactsTheViewsRead() {
        LifecycleEventLog log = log();
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        LifecycleEventRecorder recorder =
                new LifecycleEventRecorder(log, new BuildInfoProvider(buildProperties()), gitProperties(), context);

        recorder.onReady(ready(context, 1_756_000_000_000L));

        // onReady defers to recordWhenLoaded, which never blocks its caller.
        await().atMost(Duration.ofSeconds(5)).until(() -> log.events().size() == 1);
        LifecycleEvent recorded = log.events().get(0);
        assertThat(recorded.type()).isEqualTo(LifecycleEvent.Type.START);
        assertThat(recorded.epochMs()).isEqualTo(1_756_000_000_000L);
        assertThat(recorded.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(recorded.build()).containsEntry("version", "1.2.3").doesNotContainKey("artifact");
        assertThat(recorded.git()).containsEntry("branch", "dev").containsEntry("commit.id", "abc1234def5678");
    }

    /**
     * An HTTPS remote cloned with a token carries it in {@code git.remote.origin.url}, and
     * the log outlives the process that wrote it.
     */
    @Test
    void aCredentialInTheGitRemoteUrlNeverReachesTheWrittenFile(@TempDir Path directory) throws IOException {
        Properties git = new Properties();
        git.setProperty("branch", "dev");
        git.setProperty("remote.origin.url", "https://xar:ghp_secrettoken@github.com/acme/orders.git");
        Path path = directory.resolve(LifecycleEventFile.FILE_NAME);
        LifecycleEventLog log = new LifecycleEventLog(new LifecycleEventFile(path));
        log.beginLoad();
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        LifecycleEventRecorder recorder = new LifecycleEventRecorder(
                log, new BuildInfoProvider(buildProperties()), new GitProperties(git), context);

        recorder.onReady(ready(context, 1_756_000_000_000L));

        // the log persists inside the same lock its readers take, so a visible event is a written file
        await().atMost(Duration.ofSeconds(5)).until(() -> log.events().size() == 1);
        assertThat(Files.readString(path))
                .contains("\"branch\":\"dev\"")
                .doesNotContain("ghp_secrettoken", "remote.origin.url");
    }

    @Test
    void anApplicationWithoutBuildOrGitInfoStillRecordsItsStart() throws IOException {
        LifecycleEventLog log = log();
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        LifecycleEventRecorder recorder = new LifecycleEventRecorder(log, new BuildInfoProvider(null), null, context);

        // an empty classpath, so a git.properties in the test JVM's own classpath cannot answer for the application
        try (URLClassLoader classpath = new URLClassLoader(new URL[0], null)) {
            recorder.onReady(ready(context, 1L, new SpringApplication(new DefaultResourceLoader(classpath))));

            await().atMost(Duration.ofSeconds(5)).until(() -> log.events().size() == 1);
        }
        assertThat(log.events().get(0).build()).isEmpty();
        assertThat(log.events().get(0).git()).isEmpty();
    }

    /** Where the host names no git.properties, the recorder reads the application's own. */
    @Test
    void anApplicationsOwnGitPropertiesIsRecordedWhereTheHostLocatesNone(@TempDir Path resources) throws IOException {
        Files.writeString(resources.resolve("git.properties"), "git.branch=main\ngit.commit.id.abbrev=own1234\n");
        LifecycleEventLog log = log();
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        LifecycleEventRecorder recorder = new LifecycleEventRecorder(log, new BuildInfoProvider(null), null, context);

        try (URLClassLoader classpath =
                new URLClassLoader(new URL[] {resources.toUri().toURL()}, null)) {
            recorder.onReady(ready(context, 1L, new SpringApplication(new DefaultResourceLoader(classpath))));

            await().atMost(Duration.ofSeconds(5)).until(() -> log.events().size() == 1);
        }
        assertThat(log.events().get(0).git())
                .containsEntry("branch", "main")
                .containsEntry("commit.id.abbrev", "own1234");
    }

    /**
     * In a parent/child hierarchy the child's ready event is published to the parent too;
     * recording it would log a second start for one run, which then reads as an unclean exit.
     */
    @Test
    void anotherContextsReadyIsNotRecorded() {
        LifecycleEventLog log = log();
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        LifecycleEventRecorder recorder = new LifecycleEventRecorder(log, new BuildInfoProvider(null), null, context);

        recorder.onReady(ready(mock(ConfigurableApplicationContext.class), 1L));
        recorder.onReady(ready(context, 2L));

        // the starts are appended on their own threads; the own one arriving and staying alone
        // is what shows the other was never recorded
        await().atMost(Duration.ofSeconds(5))
                .during(Duration.ofMillis(300))
                .until(() -> log.events().stream().map(LifecycleEvent::epochMs).toList(), List.of(2L)::equals);
    }

    @Test
    void aClosingContextIsRecordedAsAStopAtTheClocksInstant() {
        LifecycleEventLog log = log();
        ApplicationContext context = mock(ApplicationContext.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC);
        LifecycleEventRecorder recorder =
                new LifecycleEventRecorder(log, new BuildInfoProvider(null), null, context, clock);

        recorder.onClosed(new ContextClosedEvent(context));

        assertThat(log.events())
                .extracting(LifecycleEvent::type, LifecycleEvent::epochMs)
                .containsExactly(tuple(LifecycleEvent.Type.STOP, clock.millis()));
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

        recorder.onClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

        assertThat(log.events()).isEmpty();
    }
}
