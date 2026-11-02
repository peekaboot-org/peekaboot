package org.peekaboot.backend.tracing.journal;

import static org.peekaboot.backend.testsupport.Logs.untraced;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceInput;

/** Journal and snapshot files as a crash, an older version or a hand edit can leave them. */
final class JournalTexts {

    private JournalTexts() {}

    static String snapshotHeader(@Nullable String absorbed) {
        return JournalCodec.encode(new JournalLine.SnapshotHeader(JournalCodec.VERSION, absorbed));
    }

    static String journalHeader(String id) {
        return JournalCodec.encode(new JournalLine.JournalHeader(JournalCodec.VERSION, id));
    }

    /** {@code input} as the writer appends it. */
    static String line(TraceInput input) {
        return JournalCodec.encode(JournalCodec.lineOf(input));
    }

    /** The run line the writer puts before its run's inputs. */
    static String runLine(@Nullable Instant startedAt) {
        return JournalCodec.encode(new JournalLine.RunLine(startedAt));
    }

    /** The bucket line a fold ends the snapshot with, its traces oldest first. */
    static String bucketLine(TraceBucket bucket, String... traceIds) {
        return JournalCodec.encode(new JournalLine.BucketLine(bucket, List.of(traceIds)));
    }

    /** A line written outside any trace, as the store takes it. */
    static TraceInput untracedLine(String message) {
        return new TraceInput.LogCaptured(untraced().saying(message).build());
    }

    /** A journal started under {@code journalId} holding one run's inputs, as the writer leaves it flushed. */
    static void writeJournal(JournalFiles files, String journalId, Instant runStartedAt, List<TraceInput> inputs) {
        write(
                files.journal(),
                Stream.concat(
                                Stream.of(journalHeader(journalId), runLine(runStartedAt)),
                                inputs.stream().map(JournalTexts::line))
                        .toArray(String[]::new));
    }

    /** Each line followed by a line break, as the writer leaves a file it flushed. */
    static void write(Path file, String... lines) {
        writeText(file, String.join("\n", lines) + "\n");
    }

    /** The last line without its line break, as a crash in the middle of a write leaves it. */
    static void writeCutShort(Path file, String... lines) {
        writeText(file, String.join("\n", lines));
    }

    static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
