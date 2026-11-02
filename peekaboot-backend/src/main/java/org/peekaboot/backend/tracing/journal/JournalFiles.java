package org.peekaboot.backend.tracing.journal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.storage.OwnerOnlyFiles;

/**
 * The trace journal's two files in the storage directory. No journal is appended to across runs:
 * every fold starts a fresh one under a new id, its header written first. The snapshot is replaced
 * whole and names the journal it absorbed, so a crash between writing it and starting the next
 * journal never replays that journal twice. Both assume one application per directory, like the
 * other persisted files.
 */
final class JournalFiles {

    static final String JOURNAL = "traces.journal.jsonl";
    static final String SNAPSHOT = "traces.snapshot.jsonl";

    private final Path directory;

    JournalFiles(Path directory) {
        this.directory = directory;
    }

    Path directory() {
        return directory;
    }

    Path journal() {
        return directory.resolve(JOURNAL);
    }

    Path snapshot() {
        return directory.resolve(SNAPSHOT);
    }

    /**
     * Visits what a restore replays, in arrival order: the snapshot's lines, then the journal's unless the
     * snapshot absorbed it. Both headers are checked before any line is visited, so a file of another
     * version replays nothing. A last line without a line break is what a crash mid-write leaves; it is
     * skipped without counting.
     */
    Contents read(LineVisitor visitor) throws IOException {
        return read(visitor, () -> {});
    }

    /** As {@link #read(LineVisitor)}, running {@code afterSnapshot} once the snapshot's lines are visited. */
    Contents read(LineVisitor visitor, Runnable afterSnapshot) throws IOException {
        Header snapshotHeader = header(snapshot(), true);
        Header journalHeader = header(journal(), false);
        if (snapshotHeader.foreign() || journalHeader.foreign()) {
            return new Contents(true, 0);
        }
        int unreadable = visitBody(snapshot(), visitor);
        afterSnapshot.run();
        String journalId = journalHeader.id();
        if (journalId != null && !journalId.equals(snapshotHeader.absorbed())) {
            unreadable += visitBody(journal(), visitor);
        }
        return new Contents(false, unreadable);
    }

    /** Starts an empty journal under a fresh id, replacing whatever was there, its header written and flushed. */
    OpenJournal startJournal() throws IOException {
        OwnerOnlyFiles.createDirectories(directory);
        String id = UUID.randomUUID().toString();
        OpenJournal journal = new OpenJournal(new BufferedWriter(
                new OutputStreamWriter(OwnerOnlyFiles.newOutputStream(journal()), StandardCharsets.UTF_8)));
        try {
            journal.write(new JournalLine.JournalHeader(JournalCodec.VERSION, id));
            journal.flush();
        } catch (IOException e) {
            try {
                journal.close();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        return journal;
    }

    /** The id the journal on disk was started under; null where there is none, or none readable. */
    @Nullable
    String journalId() throws IOException {
        return header(journal(), false).id();
    }

    /** Replaces the snapshot in one step, through the same mechanism as every file a store replaces whole. */
    void replaceSnapshot(OwnerOnlyFiles.Content content) throws IOException {
        OwnerOnlyFiles.replaceAtomically(snapshot(), content);
    }

    /** A header of another version leaves neither file usable. */
    void deleteBoth() throws IOException {
        Files.deleteIfExists(snapshot());
        Files.deleteIfExists(journal());
    }

    /** A missing file, an empty one, and one whose only line is cut short have no header and nothing to read. */
    private static Header header(Path file, boolean snapshot) throws IOException {
        if (!Files.exists(file)) {
            return Header.NONE;
        }
        String first;
        String second;
        try (BufferedReader reader = reader(file)) {
            first = reader.readLine();
            second = reader.readLine();
        }
        if (first == null || (second == null && !endsWithLineBreak(file))) {
            return Header.NONE;
        }
        return headerOf(JournalCodec.decode(first), snapshot);
    }

    /** A header of another type or version, or none readable, is foreign. */
    private static Header headerOf(@Nullable JournalLine line, boolean snapshot) {
        if (snapshot && line instanceof JournalLine.SnapshotHeader header && header.version() == JournalCodec.VERSION) {
            return new Header(false, null, header.absorbedJournal());
        }
        if (!snapshot && line instanceof JournalLine.JournalHeader header && header.version() == JournalCodec.VERSION) {
            return new Header(false, header.id(), null);
        }
        return Header.FOREIGN;
    }

    /** Visits every readable line after the header and returns how many others could not be read. */
    private static int visitBody(Path file, LineVisitor visitor) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        boolean endsWithLineBreak = endsWithLineBreak(file);
        int unreadable = 0;
        try (BufferedReader reader = reader(file)) {
            reader.readLine();
            String text = reader.readLine();
            while (text != null) {
                String next = reader.readLine();
                if (next != null || endsWithLineBreak) {
                    JournalLine line = JournalCodec.decode(text);
                    if (line == null) {
                        unreadable++;
                    } else {
                        visitor.visit(text, line);
                    }
                }
                text = next;
            }
        }
        return unreadable;
    }

    /** Decoding replaces malformed bytes, so a damaged byte costs its line and the read goes on. */
    private static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
    }

    private static boolean endsWithLineBreak(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long size = channel.size();
            if (size == 0) {
                return true;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(size - 1).read(last);
            return last.get(0) == '\n';
        }
    }

    /** What {@link #read} found. {@code foreign}: either file carries a header of another version, or none readable. */
    record Contents(boolean foreign, int unreadableLines) {}

    /** May throw, so a fold can write each line it visits straight into the new snapshot. */
    @FunctionalInterface
    interface LineVisitor {
        void visit(String text, JournalLine line) throws IOException;
    }

    private record Header(
            boolean foreign, @Nullable String id, @Nullable String absorbed) {
        static final Header NONE = new Header(false, null, null);
        static final Header FOREIGN = new Header(true, null, null);
    }
}
