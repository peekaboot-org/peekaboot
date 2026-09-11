package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.peekaboot.backend.testsupport.LifecycleStarts.start;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.testsupport.PosixPermissions;

class LifecycleEventFileTest {

    @TempDir
    Path directory;

    private LifecycleEventFile file() {
        return new LifecycleEventFile(directory.resolve("lifecycle.jsonl"));
    }

    @Test
    void anAbsentFileReadsAsAnEmptyLog() {
        assertThat(file().read()).isEmpty();
    }

    @Test
    void everyPropertyOfAnEventSurvivesTheRoundTrip() throws IOException {
        LifecycleEventFile file = file();
        file.write(List.of(start(1_000).pid(4711).build(), LifecycleEvent.stop(2_000, 4711)));

        List<LifecycleEvent> read = file.read();

        assertThat(read).hasSize(2);
        assertThat(read.get(0).type()).isEqualTo(LifecycleEvent.Type.START);
        assertThat(read.get(0).epochMs()).isEqualTo(1_000);
        assertThat(read.get(0).pid()).isEqualTo(4711);
        assertThat(read.get(0).build()).containsEntry("version", "1.0.0");
        assertThat(read.get(0).git()).containsEntry("branch", "dev");
        assertThat(read.get(1).type()).isEqualTo(LifecycleEvent.Type.STOP);
    }

    @Test
    void oneEventPerLineSoADamagedLineCostsOnlyItself() throws IOException {
        Path path = directory.resolve("lifecycle.jsonl");
        LifecycleEventFile file = file();
        file.write(List.of(start(1_000).build(), start(2_000).build()));
        List<String> lines = Files.readAllLines(path);
        lines.set(0, "{not json");
        Files.write(path, lines);

        List<LifecycleEvent> read = file.read();

        assertThat(read).hasSize(1);
        assertThat(read.get(0).epochMs()).isEqualTo(2_000);
    }

    /**
     * Jackson fills a record's unmentioned components with defaults, so a foreign or
     * hand-edited line can parse into an event that describes nothing. It costs itself,
     * like any other damaged line, rather than the request that reads the log.
     */
    @Test
    void aLineThatParsesIntoNoEventAtAllIsSkippedLikeADamagedOne() throws IOException {
        Path path = directory.resolve("lifecycle.jsonl");
        LifecycleEventFile file = file();
        file.write(List.of(start(2_000).build()));
        List<String> lines = new ArrayList<>();
        lines.add("{\"x\":1}");
        lines.addAll(Files.readAllLines(path));
        Files.write(path, lines);

        List<LifecycleEvent> read = file.read();

        assertThat(read).hasSize(1);
        assertThat(read.get(0).epochMs()).isEqualTo(2_000);
    }

    @Test
    void aRewriteLeavesNoTemporaryFileBehind() throws IOException {
        LifecycleEventFile file = file();
        file.write(List.of(start(1_000).build()));
        file.write(List.of(start(1_000).build(), start(2_000).build()));

        assertThat(file.read()).hasSize(2);
        try (var files = Files.list(directory)) {
            assertThat(files.map(Path::getFileName).map(Path::toString).toList())
                    .containsExactly("lifecycle.jsonl");
        }
    }

    /** The log carries build and git details verbatim; the file is the owner's business and no one else's. */
    @Test
    void theLogAndItsDirectoryAreReadableByTheOwnerAlone() throws IOException {
        PosixPermissions.assumeSupported();
        Path stateDirectory = directory.resolve("state");
        LifecycleEventFile file = new LifecycleEventFile(stateDirectory.resolve("lifecycle.jsonl"));

        file.write(List.of(start(1_000).build()));

        assertThat(PosixPermissions.of(stateDirectory)).isEqualTo("rwx------");
        assertThat(PosixPermissions.of(stateDirectory.resolve("lifecycle.jsonl")))
                .isEqualTo("rw-------");
    }

    @Test
    void aSymlinkPlantedAtTheTemporaryPathIsReplacedNotFollowed() throws IOException {
        PosixPermissions.assumeSupported();
        Path victim = directory.resolve("victim");
        Files.writeString(victim, "untouched");
        Files.createSymbolicLink(directory.resolve("lifecycle.jsonl.tmp"), victim);
        LifecycleEventFile file = file();

        file.write(List.of(start(1_000).build()));

        assertThat(Files.readString(victim)).isEqualTo("untouched");
        assertThat(file.read()).hasSize(1);
    }

    @Test
    void aFailedRewriteLeavesNoTemporaryFileBehind() throws IOException {
        Path target = directory.resolve("lifecycle.jsonl");
        Files.createDirectories(target.resolve("occupied"));
        LifecycleEventFile file = new LifecycleEventFile(target);

        assertThatThrownBy(() -> file.write(List.of(start(1_000).build()))).isInstanceOf(IOException.class);
        assertThat(directory.resolve("lifecycle.jsonl.tmp")).doesNotExist();
    }
}
