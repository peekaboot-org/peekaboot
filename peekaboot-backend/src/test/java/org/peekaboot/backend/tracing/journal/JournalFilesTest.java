package org.peekaboot.backend.tracing.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.tracing.journal.JournalTexts.journalHeader;
import static org.peekaboot.backend.tracing.journal.JournalTexts.snapshotHeader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalFilesTest {

    private static final String DISCARD_A = "{\"type\":\"discard\",\"traceId\":\"a\"}";
    private static final String DISCARD_B = "{\"type\":\"discard\",\"traceId\":\"b\"}";
    private static final String DISCARD_C = "{\"type\":\"discard\",\"traceId\":\"c\"}";

    @TempDir
    Path dir;

    private JournalFiles files;
    private final List<String> visited = new ArrayList<>();

    @BeforeEach
    void setUp() {
        files = new JournalFiles(dir);
    }

    private JournalFiles.Contents read() throws IOException {
        return files.read((text, line) -> visited.add(text));
    }

    @Test
    void readsTheSnapshotThenTheJournal() throws IOException {
        JournalTexts.write(files.snapshot(), snapshotHeader("j0"), DISCARD_A);
        JournalTexts.write(files.journal(), journalHeader("j1"), DISCARD_B, DISCARD_C);

        JournalFiles.Contents contents = read();

        assertThat(visited).containsExactly(DISCARD_A, DISCARD_B, DISCARD_C);
        assertThat(contents.foreign()).isFalse();
        assertThat(contents.unreadableLines()).isZero();
    }

    /** A crash after the snapshot moved into place and before the next journal started leaves the absorbed one behind. */
    @Test
    void skipsTheJournalTheSnapshotAbsorbed() throws IOException {
        JournalTexts.write(files.snapshot(), snapshotHeader("j1"), DISCARD_A, DISCARD_B);
        JournalTexts.write(files.journal(), journalHeader("j1"), DISCARD_B);

        read();

        assertThat(visited).containsExactly(DISCARD_A, DISCARD_B);
    }

    @Test
    void missingFilesReadAsEmpty() throws IOException {
        JournalFiles.Contents contents = read();

        assertThat(visited).isEmpty();
        assertThat(contents.foreign()).isFalse();
    }

    @Test
    void aLastLineWithoutALineBreakIsSkippedWithoutCounting() throws IOException {
        JournalTexts.writeCutShort(files.journal(), journalHeader("j1"), DISCARD_A, "{\"type\":\"discard\",\"tra");

        JournalFiles.Contents contents = read();

        assertThat(visited).containsExactly(DISCARD_A);
        assertThat(contents.unreadableLines()).isZero();
    }

    /** The cut-short rule is about the last line only: a damaged line in the middle counts, and the lines after it still read. */
    @Test
    void aDamagedLineCostsOnlyItself() throws IOException {
        JournalTexts.write(
                files.journal(),
                journalHeader("j1"),
                DISCARD_A,
                "{\"type\":\"discard\",\"tra",
                "\u0000garbage",
                DISCARD_B);

        JournalFiles.Contents contents = read();

        assertThat(visited).containsExactly(DISCARD_A, DISCARD_B);
        assertThat(contents.unreadableLines()).isEqualTo(2);
    }

    @Test
    void aHeaderOfAnotherVersionMakesBothFilesForeign() throws IOException {
        JournalTexts.write(files.snapshot(), snapshotHeader(null), DISCARD_A);
        JournalTexts.write(files.journal(), "{\"type\":\"journal\",\"version\":99,\"id\":\"j1\"}", DISCARD_B);

        JournalFiles.Contents contents = read();

        assertThat(contents.foreign()).isTrue();
        assertThat(visited).isEmpty();
    }

    @Test
    void aFileWithoutAReadableHeaderIsForeign() throws IOException {
        JournalTexts.write(files.snapshot(), DISCARD_A, DISCARD_B);

        assertThat(read().foreign()).isTrue();
    }

    /** A crash while a journal was being started leaves its header cut short; that costs the journal, never the snapshot. */
    @Test
    void aJournalCutShortInItsHeaderReadsAsEmpty() throws IOException {
        JournalTexts.write(files.snapshot(), snapshotHeader(null), DISCARD_A);
        JournalTexts.writeCutShort(files.journal(), "{\"type\":\"journal\",\"ver");

        JournalFiles.Contents contents = read();

        assertThat(contents.foreign()).isFalse();
        assertThat(visited).containsExactly(DISCARD_A);
    }

    @Test
    void aNewJournalStartsWithAHeaderUnderAFreshId() throws IOException {
        JournalTexts.write(files.journal(), journalHeader("old"), DISCARD_A);

        files.startJournal().close();
        String first = files.journalId();
        files.startJournal().close();
        String second = files.journalId();

        assertThat(JournalTexts.lines(files.journal())).containsExactly(journalHeader(second));
        assertThat(first).isNotEqualTo(second).isNotEqualTo("old");
    }

    @Test
    void deletesBothFiles() throws IOException {
        JournalTexts.write(files.snapshot(), snapshotHeader(null));
        JournalTexts.write(files.journal(), journalHeader("j1"));

        files.deleteBoth();

        assertThat(Files.exists(files.snapshot())).isFalse();
        assertThat(Files.exists(files.journal())).isFalse();
    }
}
