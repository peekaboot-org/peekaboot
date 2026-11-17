package org.peekaboot.backend.insights;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The insights journal file: what changed since the snapshot, appended record by record. */
final class InsightsJournal {

    private static final Logger log = LoggerFactory.getLogger(InsightsJournal.class);

    static final String FILE_NAME = "insights.journal";

    private final Path file;

    InsightsJournal(Path file, List<InsightsSnapshot.Level> geometry) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    /** What a load found: the history, and whether any journal record was applied to it. */
    record Replay(Optional<InsightsSnapshot> history, boolean appliedRecords) {}

    /**
     * The journal's records applied on top of {@code snapshot}, or on top of nothing without one.
     * The replay ends at the clean end of the file, at a record that cannot be read, or at one
     * that leaves a hole; what came before it stays.
     */
    Replay replayOnto(@Nullable InsightsSnapshot snapshot) {
        Optional<InsightsSnapshot> alone = Optional.ofNullable(snapshot);
        if (!Files.exists(file)) {
            return new Replay(alone, false);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            InsightsJournalCodec.Header header = readHeader(in);
            if (header == null) {
                return new Replay(alone, false);
            }
            if (snapshot != null && !InsightsSnapshot.sameGeometry(header.geometry(), snapshot.levels())) {
                log.info(
                        "Peekaboot insights: {} was written for other ring levels than {}; restoring the snapshot alone",
                        file,
                        file.resolveSibling(InsightsSnapshotStore.FILE_NAME));
                return new Replay(alone, false);
            }
            InsightsSnapshot history =
                    snapshot != null ? snapshot : InsightsJournalReplay.emptyHistory(header.geometry());
            return applyRecords(in, header, history, snapshot != null);
        } catch (IOException e) {
            log.debug("Could not read the insights journal {}", file, e);
            return new Replay(alone, false);
        }
    }

    /** The header, or null after deleting a journal whose header cannot be read. */
    private InsightsJournalCodec.@Nullable Header readHeader(DataInputStream in) {
        try {
            return InsightsJournalCodec.readHeader(in);
        } catch (IOException | RuntimeException e) {
            log.info("Peekaboot insights: ignoring unreadable {} ({})", file, e.toString());
            try {
                Files.deleteIfExists(file);
            } catch (IOException deleteFailure) {
                log.debug("Could not delete the unusable insights journal {}", file, deleteFailure);
            }
            return null;
        }
    }

    private Replay applyRecords(
            DataInputStream in, InsightsJournalCodec.Header header, InsightsSnapshot start, boolean hasSnapshot) {
        InsightsSnapshot history = start;
        boolean applied = false;
        try {
            for (Optional<InsightsSnapshot> record = InsightsJournalCodec.readRecord(in, header);
                    record.isPresent();
                    record = InsightsJournalCodec.readRecord(in, header)) {
                Optional<InsightsSnapshot> next = InsightsJournalReplay.apply(history, record.get());
                if (next.isEmpty()) {
                    log.debug("The insights journal {} leaves a hole; replay ends there", file);
                    break;
                }
                history = next.get();
                applied = true;
            }
        } catch (IOException e) {
            log.debug("The insights journal {} ends at a record that cannot be read", file, e);
        }
        return new Replay(hasSnapshot || applied ? Optional.of(history) : Optional.empty(), applied);
    }
}
