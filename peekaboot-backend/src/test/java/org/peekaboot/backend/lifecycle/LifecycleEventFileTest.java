package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LifecycleEventFileTest {

    @TempDir
    Path directory;

    private LifecycleEventFile file() {
        return new LifecycleEventFile(directory.resolve("lifecycle.jsonl"));
    }

    private static LifecycleEvent start(long epochMs) {
        return LifecycleEvent.start(epochMs, 4711, Map.of("version", "1.2.3"), Map.of("branch", "dev"));
    }

    @Test
    void anAbsentFileReadsAsAnEmptyLog() {
        assertThat(file().read()).isEmpty();
    }

    @Test
    void everyPropertyOfAnEventSurvivesTheRoundTrip() throws IOException {
        LifecycleEventFile file = file();
        file.write(List.of(start(1_000), LifecycleEvent.stop(2_000, 4711)));

        List<LifecycleEvent> read = file.read();

        assertThat(read).hasSize(2);
        assertThat(read.get(0).type()).isEqualTo(LifecycleEvent.Type.START);
        assertThat(read.get(0).epochMs()).isEqualTo(1_000);
        assertThat(read.get(0).pid()).isEqualTo(4711);
        assertThat(read.get(0).build()).containsEntry("version", "1.2.3");
        assertThat(read.get(0).git()).containsEntry("branch", "dev");
        assertThat(read.get(1).type()).isEqualTo(LifecycleEvent.Type.STOP);
    }

    @Test
    void oneEventPerLineSoADamagedLineCostsOnlyItself() throws IOException {
        Path path = directory.resolve("lifecycle.jsonl");
        LifecycleEventFile file = file();
        file.write(List.of(start(1_000), start(2_000)));
        List<String> lines = Files.readAllLines(path);
        lines.set(0, "{not json");
        Files.write(path, lines);

        List<LifecycleEvent> read = file.read();

        assertThat(read).hasSize(1);
        assertThat(read.get(0).epochMs()).isEqualTo(2_000);
    }

    @Test
    void aRewriteLeavesNoTemporaryFileBehind() throws IOException {
        LifecycleEventFile file = file();
        file.write(List.of(start(1_000)));
        file.write(List.of(start(1_000), start(2_000)));

        assertThat(file.read()).hasSize(2);
        assertThat(Files.list(directory)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .toList())
                .containsExactly("lifecycle.jsonl");
    }
}
