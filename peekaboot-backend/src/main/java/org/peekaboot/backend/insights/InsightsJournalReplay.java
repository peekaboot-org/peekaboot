package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A history with journal records applied in order. The entries sit in rings of each level's size,
 * so a record costs what it adds, not what the history holds, and the snapshot is built once.
 */
final class InsightsJournalReplay {

    private final InsightsSnapshot.Level[] levels;
    /** Per series, per level, per column, as {@link InsightsSnapshot#series()} orders them. */
    private final Map<String, DoubleRing[][]> series = new LinkedHashMap<>();

    private long writtenAtEpochMs;
    private long entriesCopied;

    InsightsJournalReplay(InsightsSnapshot history) {
        this.writtenAtEpochMs = history.writtenAtEpochMs();
        this.levels = history.levels().toArray(InsightsSnapshot.Level[]::new);
        history.series().forEach((id, byLevel) -> series.put(id, rings(byLevel)));
    }

    /** No series, every level without an end, dated 0: what a journal applies to without a snapshot. */
    static InsightsSnapshot emptyHistory(List<InsightsSnapshot.Level> geometry) {
        List<InsightsSnapshot.Level> levels = geometry.stream()
                .map(level -> new InsightsSnapshot.Level(level.intervalMs(), level.size(), 0, 0))
                .toList();
        return new InsightsSnapshot(0, levels, Map.of());
    }

    /** Adds {@code record}'s newer entries; false, with nothing added, where the record leaves a hole. */
    boolean apply(InsightsSnapshot record) {
        LevelStep[] steps = new LevelStep[levels.length];
        for (int level = 0; level < levels.length; level++) {
            Optional<LevelStep> step = stepFor(levels[level], record.levels().get(level));
            if (step.isEmpty()) {
                return false;
            }
            steps[level] = step.get();
        }

        for (String id : record.series().keySet()) {
            if (!series.containsKey(id)) {
                series.put(id, rings(gapsForEveryLevel()));
            }
        }
        series.forEach((id, rings) -> {
            List<double[][]> recordEntries = record.series().get(id);
            for (int level = 0; level < levels.length; level++) {
                double[][] fromRecord =
                        recordEntries != null ? recordEntries.get(level) : gaps(level, steps[level].fromRecord());
                add(steps[level], rings[level], fromRecord, levels[level].size());
            }
        });
        for (int level = 0; level < levels.length; level++) {
            levels[level] =
                    steps[level].resultingLevel(levels[level], record.levels().get(level));
        }
        writtenAtEpochMs = Math.max(writtenAtEpochMs, record.writtenAtEpochMs());
        return true;
    }

    /** The history with every record applied so far. */
    InsightsSnapshot result() {
        Map<String, List<double[][]>> entries = new LinkedHashMap<>();
        series.forEach((id, rings) -> {
            List<double[][]> byLevel = new ArrayList<>(rings.length);
            for (DoubleRing[] columns : rings) {
                double[][] copied = new double[columns.length][];
                for (int column = 0; column < columns.length; column++) {
                    copied[column] = columns[column].toArray();
                    entriesCopied += copied[column].length;
                }
                byLevel.add(copied);
            }
            entries.put(id, byLevel);
        });
        return new InsightsSnapshot(writtenAtEpochMs, List.of(levels), entries);
    }

    /** Entries copied into and out of the rings so far: what bounds the replay's work. */
    long entriesCopied() {
        return entriesCopied;
    }

    private static Optional<LevelStep> stepFor(InsightsSnapshot.Level history, InsightsSnapshot.Level record) {
        if (record.count() == 0 || record.endEpochMs() <= history.endEpochMs()) {
            return Optional.of(LevelStep.UNCHANGED);
        }
        if (record.count() == history.size() || history.endEpochMs() == 0) {
            return Optional.of(new LevelStep(true, record.count()));
        }
        long newer = Math.ceilDiv(Math.subtractExact(record.endEpochMs(), history.endEpochMs()), history.intervalMs());
        if (newer > record.count()) {
            return Optional.empty();
        }
        return Optional.of(new LevelStep(false, (int) newer));
    }

    /** Adds each column's last {@code step.fromRecord()} entries; the ring's size drops the oldest. */
    private void add(LevelStep step, DoubleRing[] columns, double[][] fromRecord, int size) {
        for (int column = 0; column < columns.length; column++) {
            if (step.replace()) {
                columns[column] = new DoubleRing(size);
            }
            double[] values = fromRecord[column];
            for (int i = values.length - step.fromRecord(); i < values.length; i++) {
                columns[column].add(values[i]);
            }
            entriesCopied += step.fromRecord();
        }
    }

    private DoubleRing[][] rings(List<double[][]> byLevel) {
        DoubleRing[][] rings = new DoubleRing[levels.length][];
        for (int level = 0; level < levels.length; level++) {
            double[][] columns = byLevel.get(level);
            rings[level] = new DoubleRing[columns.length];
            for (int column = 0; column < columns.length; column++) {
                rings[level][column] = new DoubleRing(levels[level].size());
                rings[level][column].restore(columns[column]);
                entriesCopied += columns[column].length;
            }
        }
        return rings;
    }

    /** What a series the history lacks holds instead, level by level. */
    private List<double[][]> gapsForEveryLevel() {
        List<double[][]> byLevel = new ArrayList<>(levels.length);
        for (int level = 0; level < levels.length; level++) {
            byLevel.add(gaps(level, levels[level].count()));
        }
        return byLevel;
    }

    private static double[][] gaps(int level, int length) {
        int columns = level == 0 ? 1 : InsightsSnapshot.STAT_COLUMNS.size();
        double[][] gaps = new double[columns][length];
        for (int column = 0; column < columns; column++) {
            boolean samples = level > 0 && column == columns - 1;
            Arrays.fill(gaps[column], samples ? 0.0 : Double.NaN);
        }
        return gaps;
    }

    /** What the record does to one level: leave it, replace it, or append its last {@code fromRecord} entries. */
    private record LevelStep(boolean replace, int fromRecord) {

        static final LevelStep UNCHANGED = new LevelStep(false, 0);

        InsightsSnapshot.Level resultingLevel(InsightsSnapshot.Level history, InsightsSnapshot.Level record) {
            if (!replace && fromRecord == 0) {
                return history;
            }
            int count = replace ? record.count() : Math.min(history.size(), history.count() + fromRecord);
            return new InsightsSnapshot.Level(history.intervalMs(), history.size(), record.endEpochMs(), count);
        }
    }
}
