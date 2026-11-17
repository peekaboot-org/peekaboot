package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InsightsJournalReplayTest {

    private static final String CPU = "cpu.process";
    private static final int STAT_COLUMNS = InsightsSnapshot.STAT_COLUMNS.size();
    private static final Set<String> CONFIGURED_SERIES = Set.of(CPU, "a", "b", "c");

    private static final List<InsightsSnapshot.Level> TWO_LEVELS =
            List.of(new InsightsSnapshot.Level(10_000, 5, 0, 0), new InsightsSnapshot.Level(60_000, 4, 0, 0));

    @Test
    void entriesDatedAtOrBeforeTheLevelsEndAreSkipped() {
        InsightsSnapshot history = oneLevel(1_000, 30_000, 1, 2, 3);
        InsightsSnapshot record = oneLevel(2_000, 30_000, 2, 3);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, CPU)).containsExactly(1, 2, 3);
        assertThat(result.levels().get(0).endEpochMs()).isEqualTo(30_000);
    }

    @Test
    void aRecordThatStraddlesTheEndAddsOnlyItsNewerEntries() {
        InsightsSnapshot history = oneLevel(1_000, 30_000, 1, 2, 3);
        InsightsSnapshot record = oneLevel(2_000, 50_000, 3, 4, 5);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, CPU)).containsExactly(1, 2, 3, 4, 5);
        assertThat(result.levels().get(0).endEpochMs()).isEqualTo(50_000);
        assertThat(result.levels().get(0).count()).isEqualTo(5);
    }

    @Test
    void aRecordThatStartsMoreThanOneIntervalPastTheEndLeavesAHole() {
        InsightsSnapshot history = oneLevel(1_000, 30_000, 1, 2, 3);
        InsightsSnapshot record = oneLevel(2_000, 60_000, 5, 6);

        assertThat(applied(history, record)).isEmpty();
    }

    @Test
    void aFullRingRecordReplacesTheLevelAcrossAnyGap() {
        InsightsSnapshot history = oneLevel(1_000, 30_000, 1, 2, 3);
        InsightsSnapshot record = oneLevel(2_000, 1_000_000, 11, 12, 13, 14, 15);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, CPU)).containsExactly(11, 12, 13, 14, 15);
        assertThat(result.levels().get(0).endEpochMs()).isEqualTo(1_000_000);
    }

    @Test
    void aFullRingRecordDatedAtOrBeforeTheEndIsSkipped() {
        InsightsSnapshot history = oneLevel(1_000, 100_000, 1, 2, 3, 4, 5);
        InsightsSnapshot record = oneLevel(2_000, 90_000, 9, 9, 9, 9, 9);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, CPU)).containsExactly(1, 2, 3, 4, 5);
        assertThat(result.levels().get(0).endEpochMs()).isEqualTo(100_000);
    }

    @Test
    void aLevelWithoutAnEndTakesTheRecordAsItIs() {
        InsightsSnapshot history = oneLevel(1_000, 0);
        InsightsSnapshot record = oneLevel(2_000, 40_000, 7, 8);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, CPU)).containsExactly(7, 8);
        assertThat(result.levels().get(0).endEpochMs()).isEqualTo(40_000);
        assertThat(result.levels().get(0).count()).isEqualTo(2);
    }

    @Test
    void aLevelKeepsAtMostItsSizeOldestDropped() {
        InsightsSnapshot history = oneLevel(1_000, 50_000, 1, 2, 3, 4, 5);
        InsightsSnapshot record = oneLevel(2_000, 70_000, 6, 7);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, CPU)).containsExactly(3, 4, 5, 6, 7);
        assertThat(result.levels().get(0).count()).isEqualTo(5);
    }

    @Test
    void aLevelTheRecordDoesNotMoveIsLeftAsItIs() {
        InsightsSnapshot history = twoLevels(1_000, 30_000, new double[] {1, 2, 3}, 60_000, new double[] {10});
        InsightsSnapshot record = twoLevels(2_000, 40_000, new double[] {4}, 60_000, new double[] {});

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, CPU)).containsExactly(1, 2, 3, 4);
        assertThat(result.levels().get(1).endEpochMs()).isEqualTo(60_000);
        assertThat(result.levels().get(1).count()).isEqualTo(1);
        assertThat(result.series().get(CPU).get(1)[0]).containsExactly(10);
    }

    @Test
    void aSeriesOnlyInTheHistoryGetsGapsForWhatTheRecordAdds() {
        Map<String, List<double[][]>> historySeries = new LinkedHashMap<>();
        historySeries.put("a", List.of(level0Columns(1, 2), statColumns(10)));
        historySeries.put("b", List.of(level0Columns(3, 4), statColumns(20)));
        InsightsSnapshot history = new InsightsSnapshot(1_000, levels(30_000, 2, 60_000, 1), historySeries);
        Map<String, List<double[][]>> recordSeries = new LinkedHashMap<>();
        recordSeries.put("a", List.of(level0Columns(5), statColumns(30)));
        InsightsSnapshot record = new InsightsSnapshot(2_000, levels(40_000, 1, 120_000, 1), recordSeries);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(level0(result, "b")).containsExactly(3, 4, Double.NaN);
        double[][] level1 = result.series().get("b").get(1);
        assertThat(level1[0]).hasSize(2);
        assertThat(level1[0][0]).isEqualTo(20);
        for (int column = 0; column < STAT_COLUMNS - 1; column++) {
            assertThat(level1[column][1]).isNaN();
        }
        assertThat(level1[STAT_COLUMNS - 1][1]).isZero();
    }

    @Test
    void aSeriesOnlyInTheRecordGetsGapsForWhatTheHistoryHolds() {
        Map<String, List<double[][]>> historySeries = new LinkedHashMap<>();
        historySeries.put("a", List.of(level0Columns(1, 2, 3), statColumns(10)));
        InsightsSnapshot history = new InsightsSnapshot(1_000, levels(30_000, 3, 60_000, 1), historySeries);
        Map<String, List<double[][]>> recordSeries = new LinkedHashMap<>();
        recordSeries.put("a", List.of(level0Columns(4), statColumns(30)));
        recordSeries.put("c", List.of(level0Columns(9), statColumns(40)));
        InsightsSnapshot record = new InsightsSnapshot(2_000, levels(40_000, 1, 120_000, 1), recordSeries);

        InsightsSnapshot result = applied(history, record).orElseThrow();

        assertThat(result.series().keySet()).containsExactly("a", "c");
        assertThat(level0(result, "c")).containsExactly(Double.NaN, Double.NaN, Double.NaN, 9);
        double[][] level1 = result.series().get("c").get(1);
        assertThat(level1[0][0]).isNaN();
        assertThat(level1[STAT_COLUMNS - 1][0]).isZero();
        assertThat(level1[0][1]).isEqualTo(40);
    }

    @Test
    void aHoleOnAnyLevelLeavesTheWholeRecordUnapplied() {
        InsightsSnapshot history = twoLevels(1_000, 30_000, new double[] {1, 2, 3}, 60_000, new double[] {10});
        InsightsJournalReplay replay = new InsightsJournalReplay(history, CONFIGURED_SERIES);
        InsightsSnapshot record = twoLevels(2_000, 40_000, new double[] {4}, 240_000, new double[] {11});

        assertThat(replay.apply(record)).isFalse();

        InsightsSnapshot result = replay.result();
        assertThat(level0(result, CPU)).containsExactly(1, 2, 3);
        assertThat(result.levels().get(0).endEpochMs()).isEqualTo(30_000);
        assertThat(result.series().get(CPU).get(1)[0]).containsExactly(10);
        assertThat(result.writtenAtEpochMs()).isEqualTo(1_000);
    }

    @Test
    void theResultIsDatedByTheLaterOfHistoryAndRecord() {
        InsightsSnapshot history = oneLevel(5_000, 30_000, 1, 2, 3);

        Optional<InsightsSnapshot> newer = applied(history, oneLevel(9_000, 40_000, 4));
        Optional<InsightsSnapshot> stale = applied(history, oneLevel(2_000, 40_000, 4));

        assertThat(newer.orElseThrow().writtenAtEpochMs()).isEqualTo(9_000);
        assertThat(stale.orElseThrow().writtenAtEpochMs()).isEqualTo(5_000);
    }

    @Test
    void aRecordCostsWhatItAddsNotWhatTheHistoryHolds() {
        List<InsightsSnapshot.Level> fullRings = List.of(
                new InsightsSnapshot.Level(10_000, 100, 1_000_000, 100),
                new InsightsSnapshot.Level(60_000, 50, 960_000, 50));
        Map<String, List<double[][]>> historySeries = new LinkedHashMap<>();
        for (String id : List.of("a", "b", "c")) {
            historySeries.put(id, List.of(new double[1][100], new double[STAT_COLUMNS][50]));
        }
        InsightsJournalReplay replay =
                new InsightsJournalReplay(new InsightsSnapshot(1_000, fullRings, historySeries), CONFIGURED_SERIES);
        int records = 200;
        for (int entry = 1; entry <= records; entry++) {
            List<InsightsSnapshot.Level> levels = List.of(
                    new InsightsSnapshot.Level(10_000, 100, 1_000_000 + entry * 10_000L, 1),
                    new InsightsSnapshot.Level(60_000, 50, 960_000, 0));
            Map<String, List<double[][]>> recordSeries = new LinkedHashMap<>();
            for (String id : historySeries.keySet()) {
                recordSeries.put(id, List.of(level0Columns(entry), new double[STAT_COLUMNS][0]));
            }
            assertThat(replay.apply(new InsightsSnapshot(2_000, levels, recordSeries)))
                    .isTrue();
        }

        InsightsSnapshot result = replay.result();

        assertThat(level0(result, "a")).hasSize(100).startsWith(101).endsWith(200);
        long historyEntries = 3L * (100 + 50 * STAT_COLUMNS);
        long journalEntries = 3L * records;
        // Each history entry is copied in once and out once, each journal entry once.
        assertThat(replay.entriesCopied()).isLessThanOrEqualTo(2 * historyEntries + journalEntries);
    }

    @Test
    void aSeriesTheConfigurationLacksIsLeftOutBeforeAnythingIsBuiltForIt() {
        Map<String, List<double[][]>> historySeries = new LinkedHashMap<>();
        historySeries.put(CPU, List.<double[][]>of(level0Columns(1, 2, 3)));
        historySeries.put("gone", List.<double[][]>of(level0Columns(7, 8, 9)));
        Map<String, List<double[][]>> recordSeries = new LinkedHashMap<>();
        recordSeries.put(CPU, List.<double[][]>of(level0Columns(4)));
        recordSeries.put("also.gone", List.<double[][]>of(level0Columns(5)));
        List<InsightsSnapshot.Level> ending30s = List.of(new InsightsSnapshot.Level(10_000, 5, 30_000, 3));
        List<InsightsSnapshot.Level> ending40s = List.of(new InsightsSnapshot.Level(10_000, 5, 40_000, 1));
        InsightsJournalReplay replay =
                new InsightsJournalReplay(new InsightsSnapshot(1_000, ending30s, historySeries), Set.of(CPU));

        assertThat(replay.apply(new InsightsSnapshot(2_000, ending40s, recordSeries)))
                .isTrue();
        InsightsSnapshot result = replay.result();

        assertThat(result.series()).containsOnlyKeys(CPU);
        assertThat(level0(result, CPU)).containsExactly(1, 2, 3, 4);
        // CPU's three entries in, its one new entry, its four out: nothing for the other two.
        assertThat(replay.entriesCopied()).isEqualTo(8);
    }

    @Test
    void anEmptyHistoryHasTheGeometryAndNothingElse() {
        InsightsSnapshot empty = InsightsJournalReplay.emptyHistory(TWO_LEVELS);

        assertThat(empty.writtenAtEpochMs()).isZero();
        assertThat(empty.series()).isEmpty();
        assertThat(empty.levels())
                .containsExactly(
                        new InsightsSnapshot.Level(10_000, 5, 0, 0), new InsightsSnapshot.Level(60_000, 4, 0, 0));
    }

    /** {@code history} with {@code record} applied, or empty where the record leaves a hole. */
    private static Optional<InsightsSnapshot> applied(InsightsSnapshot history, InsightsSnapshot record) {
        InsightsJournalReplay replay = new InsightsJournalReplay(history, CONFIGURED_SERIES);
        return replay.apply(record) ? Optional.of(replay.result()) : Optional.empty();
    }

    private static InsightsSnapshot oneLevel(long writtenAt, long end, double... values) {
        List<InsightsSnapshot.Level> levels = List.of(new InsightsSnapshot.Level(10_000, 5, end, values.length));
        return new InsightsSnapshot(writtenAt, levels, Map.of(CPU, List.<double[][]>of(level0Columns(values))));
    }

    private static InsightsSnapshot twoLevels(
            long writtenAt, long end0, double[] level0, long end1, double[] level1Values) {
        List<InsightsSnapshot.Level> levels = levels(end0, level0.length, end1, level1Values.length);
        double[][] level1 = new double[STAT_COLUMNS][];
        Arrays.setAll(level1, column -> level1Values.clone());
        return new InsightsSnapshot(writtenAt, levels, Map.of(CPU, List.of(level0Columns(level0), level1)));
    }

    private static List<InsightsSnapshot.Level> levels(long end0, int count0, long end1, int count1) {
        return List.of(
                new InsightsSnapshot.Level(10_000, 5, end0, count0),
                new InsightsSnapshot.Level(60_000, 4, end1, count1));
    }

    private static double[][] level0Columns(double... values) {
        return new double[][] {values};
    }

    private static double[][] statColumns(double base) {
        double[][] columns = new double[STAT_COLUMNS][];
        Arrays.setAll(columns, column -> new double[] {base + column});
        return columns;
    }

    private static double[] level0(InsightsSnapshot snapshot, String id) {
        return snapshot.series().get(id).get(0)[0];
    }
}
