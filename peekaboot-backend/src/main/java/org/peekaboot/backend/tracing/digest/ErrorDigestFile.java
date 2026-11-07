package org.peekaboot.backend.tracing.digest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.storage.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code errors.jsonl} in the storage directory: a header naming the format's version, then one
 * {@link DigestedError} a line. The trace journal's writer replaces it whole on a flush that finds the
 * digest changed, and once more as it stops; its restore reads it back before any live input arrives.
 * Like the trace files it is a cache, so what is wrong with it costs only what it held and is said once,
 * at INFO. A failed write warns once and ends writing for the run.
 */
public final class ErrorDigestFile {

    static final String FILE_NAME = "errors.jsonl";

    /** Raised with every change to a line's shape; a file of another version is discarded whole. */
    static final int VERSION = 1;

    /** The written count of a file that holds what the digest did not keep; the digest's count never goes below zero. */
    private static final long STALE = -1;

    private static final Logger log = LoggerFactory.getLogger(ErrorDigestFile.class);

    /** Its own mapper, so the host application's Jackson configuration never reaches this file's format. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path file;
    private final ErrorDigest digest;

    // the journal's writer thread's own: restore() runs on it before the first flush()
    private long writtenChanges;
    private boolean failed;

    public ErrorDigestFile(Path directory, ErrorDigest digest) {
        this.file = directory.resolve(FILE_NAME);
        this.digest = digest;
    }

    /** The file's header line. */
    record Header(int version) {}

    /** Puts what the file holds into the digest. A missing file restores nothing and says nothing. */
    public void restore() {
        if (!Files.exists(file)) {
            return;
        }
        List<DigestedError> entries = new ArrayList<>();
        int unreadable = 0;
        boolean foreign;
        try (BufferedReader reader = reader()) {
            foreign = !isCurrentHeader(reader.readLine());
            for (String line = foreign ? null : reader.readLine(); line != null; line = reader.readLine()) {
                DigestedError entry = decode(line);
                if (entry == null) {
                    unreadable++;
                } else {
                    entries.add(entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.info("Peekaboot errors: cannot read {} ({}); starting without it", file, e.toString());
            return;
        }
        if (foreign) {
            log.info(
                    "Peekaboot errors: {} comes from another Peekaboot version or starts with a damaged header;"
                            + " starting without it",
                    file);
            deleteQuietly();
            return;
        }
        if (unreadable > 0) {
            log.info("Peekaboot errors: skipped {} unreadable lines in {}; restored the rest", unreadable, file);
        }
        int held = digest.entries().size();
        digest.restore(entries);
        boolean keptAll = unreadable == 0 && digest.entries().size() - held == entries.size();
        // the first flush then rewrites the file, so the next start reads it clean and says nothing
        writtenChanges = keptAll ? digest.changes() : STALE;
    }

    /** Replaces the file where the digest changed since the last write. */
    public void flush() {
        if (failed) {
            return;
        }
        ErrorDigest.Changed changed = digest.changedSince(writtenChanges);
        if (changed == null) {
            return;
        }
        try {
            OwnerOnlyFiles.replaceAtomically(file, out -> write(out, changed.entries()));
            writtenChanges = changed.changes();
        } catch (IOException | RuntimeException e) {
            failed = true;
            log.warn(
                    "Peekaboot errors: cannot write {} ({}); the error digest from here on will not survive a"
                            + " restart",
                    file,
                    e.toString());
        }
    }

    private static void write(OutputStream out, List<DigestedError> entries) throws IOException {
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write(MAPPER.writeValueAsString(new Header(VERSION)));
        writer.write('\n');
        for (DigestedError entry : entries) {
            writer.write(MAPPER.writeValueAsString(entry));
            writer.write('\n');
        }
        writer.flush();
    }

    private static boolean isCurrentHeader(@Nullable String line) {
        if (line == null) {
            return false;
        }
        try {
            return MAPPER.readValue(line, Header.class).version() == VERSION;
        } catch (JacksonException e) {
            return false;
        }
    }

    /** The entry, or null where the line is damaged or misses what an entry needs. */
    @Nullable
    private static DigestedError decode(String line) {
        try {
            return MAPPER.readValue(line, DigestedError.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Decoding replaces malformed bytes, so a damaged byte costs its line and the read goes on. */
    private BufferedReader reader() throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
    }

    private void deleteQuietly() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete the unusable error digest {}", file, e);
        }
    }
}
