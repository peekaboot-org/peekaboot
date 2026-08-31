package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LifecycleEventLogTest {

    @TempDir
    Path directory;

    private LifecycleEventFile file() {
        return new LifecycleEventFile(directory.resolve(LifecycleEventFile.FILE_NAME));
    }

    private static LifecycleEvent start(long epochMs) {
        return LifecycleEvent.start(epochMs, 4711, Map.of("version", "1.2.3"), Map.of("branch", "dev"));
    }

    private static LifecycleEventLog loaded(LifecycleEventFile file) {
        LifecycleEventLog log = new LifecycleEventLog(file);
        log.beginLoad();
        return log;
    }

    @Test
    void aRecordedStartOutlivesTheRunThatWroteIt() {
        LifecycleEventLog first = loaded(file());
        first.recordAndPersist(start(1_000));

        assertThat(loaded(file()).events()).extracting(LifecycleEvent::epochMs).containsExactly(1_000L);
    }

    @Test
    void theStartIsAppendedWithoutTheCallerWaitingForTheLoad() {
        LifecycleEventLog log = loaded(file());
        log.recordWhenLoaded(start(1_000));

        await().atMost(Duration.ofSeconds(5)).until(() -> log.events().size() == 1);
    }

    @Test
    void theLogNeverGrowsPastItsCap() throws IOException {
        LifecycleEventFile file = file();
        List<LifecycleEvent> existing = new ArrayList<>();
        for (int i = 0; i < LifecycleEventLog.MAX_EVENTS; i++) {
            existing.add(start(i));
        }
        file.write(existing);

        LifecycleEventLog log = loaded(file);
        log.recordAndPersist(start(9_999));

        assertThat(log.events()).hasSize(LifecycleEventLog.MAX_EVENTS);
        assertThat(log.events().get(0).epochMs()).isEqualTo(1); // the oldest is gone
        assertThat(log.events().get(LifecycleEventLog.MAX_EVENTS - 1).epochMs()).isEqualTo(9_999);
        assertThat(Files.readAllLines(directory.resolve(LifecycleEventFile.FILE_NAME)))
                .hasSize(LifecycleEventLog.MAX_EVENTS);
    }

    @Test
    void withoutAFileTheCurrentRunIsStillRemembered() {
        LifecycleEventLog log = loaded(null);
        log.recordAndPersist(start(1_000));

        assertThat(log.events()).hasSize(1);
        assertThat(Files.exists(directory.resolve(LifecycleEventFile.FILE_NAME)))
                .isFalse();
    }

    @Test
    void aStopIsDroppedRatherThanWrittenOverHistoryThatNeverLoaded() throws IOException {
        LifecycleEventFile file = file();
        file.write(List.of(start(1_000), start(2_000)));

        LifecycleEventLog log = new LifecycleEventLog(file); // beginLoad() deliberately not called
        log.recordAndPersist(LifecycleEvent.stop(3_000, 4711));

        assertThat(file.read()).hasSize(2);
    }
}
