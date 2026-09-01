package org.peekaboot.testingapp.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.lifecycle.LifecycleEvent;
import org.peekaboot.backend.lifecycle.LifecycleEventFile;

/**
 * A run history for the application to find when it boots, so the Lifecycle tab has
 * more than the application's own run to show. {@link #writeTo} writes a
 * {@code lifecycle.jsonl} with the production {@link LifecycleEventFile} and
 * {@link LifecycleEvent}'s own factories - the fixture can never drift from the format
 * the reader expects, because if it did, every consumer would fail instead of proving
 * nothing.
 *
 * <p>Runs are chronologically ascending, oldest first (index {@code 0..runCount-1}):
 * <ul>
 *   <li>every run ends cleanly with a 2h30m uptime and a 45m gap before the next start,
 *       except run {@code uncleanIndex};
 *   <li>run {@code uncleanIndex} has a start with no matching stop - a {@code kill -9} -
 *       so it renders an Unclean exit badge with a dash duration, and the run after it
 *       (whose preceding event is a start, not a stop) renders a dash downtime;
 *   <li>runs before {@code versionChangeIndex} carry version 1.0.0, the rest 1.1.0, so
 *       run {@code versionChangeIndex} is flagged a deployment for its version change;
 *   <li>runs before {@code branchChangeIndex} carry branch "main" and one commit, the
 *       rest "feature/redesign" and another, so run {@code branchChangeIndex} is flagged
 *       a deployment for its branch and commit change.
 * </ul>
 *
 * <p>The seeded span is anchored 30 days before "now", so it finishes well before the
 * application's own (real-clock) start - comfortably clear of any collision, and still
 * the oldest-to-newest order the reader expects.
 */
record LifecycleHistoryFixture(int runCount, int uncleanIndex, int versionChangeIndex, int branchChangeIndex) {

    private static final long RUN_DURATION_MS =
            Duration.ofHours(2).plusMinutes(30).toMillis();
    private static final long GAP_MS = Duration.ofMinutes(45).toMillis();

    void writeTo(Path file) {
        long cursor = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli();
        List<LifecycleEvent> events = new ArrayList<>();
        for (int i = 0; i < runCount; i++) {
            long pid = 1000L + i;
            events.add(LifecycleEvent.start(cursor, pid, buildOf(i), gitOf(i)));
            cursor += RUN_DURATION_MS;
            if (i != uncleanIndex) {
                events.add(LifecycleEvent.stop(cursor, pid));
            }
            cursor += GAP_MS;
        }
        try {
            new LifecycleEventFile(file).write(events);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to seed " + file, e);
        }
    }

    private Map<String, String> buildOf(int i) {
        Map<String, String> build = new LinkedHashMap<>();
        build.put("version", i < versionChangeIndex ? "1.0.0" : "1.1.0");
        return build;
    }

    private Map<String, String> gitOf(int i) {
        Map<String, String> git = new LinkedHashMap<>();
        boolean deployed = i >= branchChangeIndex;
        git.put("branch", deployed ? "feature/redesign" : "main");
        git.put(
                "commit.id",
                deployed ? "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" : "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        return git;
    }
}
