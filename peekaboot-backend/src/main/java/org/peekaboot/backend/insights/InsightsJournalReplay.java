package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class InsightsJournalReplay {

    private InsightsJournalReplay() {}

    /** {@code history} with {@code record}'s newer entries, or empty where the record leaves a hole. */
    static Optional<InsightsSnapshot> apply(InsightsSnapshot history, InsightsSnapshot record) {
        List<LevelStep> steps = new ArrayList<>(history.levels().size());
        for (int level = 0; level < history.levels().size(); level++) {
            Optional<LevelStep> step =
                    stepFor(history.levels().get(level), record.levels().get(level));
            if (step.isEmpty()) {
                return Optional.empty();
            }
            steps.add(step.get());
        }

        List<InsightsSnapshot.Level> levels = new ArrayList<>(steps.size());
        for (int level = 0; level < steps.size(); level++) {
            levels.add(steps.get(level)
                    .resultingLevel(history.levels().get(level), record.levels().get(level)));
        }
        Map<String, List<double[][]>> series = new LinkedHashMap<>();
        for (String id : history.series().keySet()) {
            series.put(
                    id, mergedSeries(history.series().get(id), record.series().get(id), history, record, steps));
        }
        for (String id : record.series().keySet()) {
            if (!series.containsKey(id)) {
                series.put(id, mergedSeries(null, record.series().get(id), history, record, steps));
            }
        }
        return Optional.of(
                new InsightsSnapshot(Math.max(history.writtenAtEpochMs(), record.writtenAtEpochMs()), levels, series));
    }

    /** No series, every level without an end, dated 0: what a journal applies to without a snapshot. */
    static InsightsSnapshot emptyHistory(List<InsightsSnapshot.Level> geometry) {
        List<InsightsSnapshot.Level> levels = geometry.stream()
                .map(level -> new InsightsSnapshot.Level(level.intervalMs(), level.size(), 0, 0))
                .toList();
        return new InsightsSnapshot(0, levels, Map.of());
    }

    private static Optional<LevelStep> stepFor(InsightsSnapshot.Level history, InsightsSnapshot.Level record) {
        if (record.count() == 0 || record.endEpochMs() <= history.endEpochMs()) {
            return Optional.of(LevelStep.UNCHANGED);
        }
        if (record.count() == history.size() || history.endEpochMs() == 0) {
            return Optional.of(new LevelStep(true, record.count()));
        }
        long newer = Math.ceilDiv(record.endEpochMs() - history.endEpochMs(), history.intervalMs());
        if (newer > record.count()) {
            return Optional.empty();
        }
        return Optional.of(new LevelStep(false, (int) newer));
    }

    /** {@code historyEntries} or {@code recordEntries} null stands for a series the snapshot lacks. */
    private static List<double[][]> mergedSeries(
            List<double[][]> historyEntries,
            List<double[][]> recordEntries,
            InsightsSnapshot history,
            InsightsSnapshot record,
            List<LevelStep> steps) {
        List<double[][]> merged = new ArrayList<>(steps.size());
        for (int level = 0; level < steps.size(); level++) {
            LevelStep step = steps.get(level);
            int historyCount = history.levels().get(level).count();
            int recordCount = record.levels().get(level).count();
            double[][] fromHistory = historyEntries != null ? historyEntries.get(level) : gaps(level, historyCount);
            double[][] fromRecord = recordEntries != null ? recordEntries.get(level) : gaps(level, recordCount);
            merged.add(step.merge(
                    fromHistory, fromRecord, history.levels().get(level).size()));
        }
        return merged;
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

        double[][] merge(double[][] fromHistory, double[][] fromRecordColumns, int size) {
            double[][] merged = new double[fromHistory.length][];
            for (int column = 0; column < merged.length; column++) {
                merged[column] = mergeColumn(fromHistory[column], fromRecordColumns[column], size);
            }
            return merged;
        }

        private double[] mergeColumn(double[] history, double[] record, int size) {
            if (replace) {
                return record.clone();
            }
            double[] joined = new double[history.length + fromRecord];
            System.arraycopy(history, 0, joined, 0, history.length);
            System.arraycopy(record, record.length - fromRecord, joined, history.length, fromRecord);
            return Arrays.copyOfRange(joined, Math.max(0, joined.length - size), joined.length);
        }
    }
}
