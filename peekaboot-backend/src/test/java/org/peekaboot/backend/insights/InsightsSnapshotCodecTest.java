package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InsightsSnapshotCodecTest {

    private static InsightsSnapshot snapshot() {
        double[] ticks = {1.0, Double.NaN, 3.0};
        double[][] stats = new double[8][2];
        for (int column = 0; column < 8; column++) {
            stats[column][0] = column;
            stats[column][1] = Double.NaN;
        }
        return new InsightsSnapshot(
                1_756_000_000_000L,
                List.of(
                        new InsightsSnapshot.Level(10_000, 90, 1_756_000_010_000L, 3),
                        new InsightsSnapshot.Level(60_000, 1440, 1_756_000_060_000L, 2)),
                Map.of("cpu.process", List.of(new double[][] {ticks}, stats)));
    }

    /** What {@code /insights/schema-2.snapshot} holds: two series, gaps in both levels, a window without samples. */
    private static InsightsSnapshot pinnedSnapshot() {
        Map<String, List<double[][]>> series = new LinkedHashMap<>();
        series.put("cpu.process", List.of(new double[][] {{12.5, Double.NaN, 37.25}}, windows(0.5, Double.NaN, 0)));
        series.put("heap.used", List.of(new double[][] {{1024.0, 2048.0, Double.NaN}}, windows(100, 200, 6)));
        return new InsightsSnapshot(
                1_756_000_000_000L,
                List.of(
                        new InsightsSnapshot.Level(10_000, 90, 1_756_000_010_000L, 3),
                        new InsightsSnapshot.Level(60_000, 1440, 1_756_000_060_000L, 2)),
                series);
    }

    /** Two windows: each statistic counts up from {@code first} and {@code second}, six samples behind the first. */
    private static double[][] windows(double first, double second, double secondSamples) {
        double[][] columns = new double[8][];
        for (int column = 0; column < 7; column++) {
            columns[column] = new double[] {first + column, second + column};
        }
        columns[7] = new double[] {6.0, secondSamples};
        return columns;
    }

    private static byte[] encoded(InsightsSnapshot snapshot) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            InsightsSnapshotCodec.write(out, snapshot);
        }
        return bytes.toByteArray();
    }

    private static InsightsSnapshot decoded(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return InsightsSnapshotCodec.readBody(in, InsightsSnapshotCodec.readHeader(in));
        }
    }

    /** The column order is the file format: a snapshot written today must read back under tomorrow's build. */
    @Test
    void theStatColumnOrderIsTheFileFormat() {
        assertThat(InsightsSnapshot.STAT_COLUMNS)
                .containsExactly("min", "max", "avg", "median", "p90", "p95", "p99", "samples");
    }

    @Test
    void aSnapshotSurvivesTheRoundTripIncludingItsGaps() throws IOException {
        InsightsSnapshot restored = decoded(encoded(snapshot()));

        assertThat(restored.writtenAtEpochMs()).isEqualTo(1_756_000_000_000L);
        assertThat(restored.levels())
                .containsExactly(
                        new InsightsSnapshot.Level(10_000, 90, 1_756_000_010_000L, 3),
                        new InsightsSnapshot.Level(60_000, 1440, 1_756_000_060_000L, 2));
        assertThat(restored.series()).containsOnlyKeys("cpu.process");
        assertThat(restored.series().get("cpu.process").get(0)[0]).containsExactly(1.0, Double.NaN, 3.0);
        assertThat(restored.series().get("cpu.process").get(1)).hasDimensions(8, 2);
        assertThat(restored.series().get("cpu.process").get(1)[7]).containsExactly(7.0, Double.NaN);
    }

    /**
     * Pins schema 2 as 1.3.0 writes it. The file was generated once by this codec, whose write path is
     * unchanged since 1.3.0; a byte that changes here strands every history an upgrade carries over.
     */
    @Test
    void aPinnedSchemaTwoFileDecodesToItsValuesAndEncodesBackToTheSameBytes() throws IOException {
        byte[] pinned;
        try (InputStream in = InsightsSnapshotCodecTest.class.getResourceAsStream("/insights/schema-2.snapshot")) {
            pinned = in.readAllBytes();
        }
        InsightsSnapshot expected = pinnedSnapshot();

        InsightsSnapshot decoded = decoded(pinned);

        assertThat(decoded.writtenAtEpochMs()).isEqualTo(expected.writtenAtEpochMs());
        assertThat(decoded.levels()).isEqualTo(expected.levels());
        assertThat(decoded.series().keySet()).containsExactly("cpu.process", "heap.used");
        expected.series().forEach((id, byLevel) -> {
            for (int level = 0; level < byLevel.size(); level++) {
                double[][] columns = byLevel.get(level);
                assertThat(decoded.series().get(id).get(level)).hasNumberOfRows(columns.length);
                for (int column = 0; column < columns.length; column++) {
                    assertThat(decoded.series().get(id).get(level)[column])
                            .as("%s, level %d, column %d", id, level, column)
                            .containsExactly(columns[column]);
                }
            }
        });
        assertThat(encoded(expected)).isEqualTo(pinned);
    }

    /** A misaligned file only fails on the next read, so the write is where it has to fail. */
    @Test
    void aColumnThatContradictsTheDeclaredCountIsRefusedRatherThanWritten() {
        InsightsSnapshot mismatched = new InsightsSnapshot(
                1L,
                List.of(new InsightsSnapshot.Level(10_000, 90, 20_000, 3)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{1.0, 2.0}})));

        assertThatThrownBy(() -> encoded(mismatched))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cpu.process")
                .hasMessageContaining("level 0");
    }

    @Test
    void aSeriesCoveringFewerLevelsThanTheHeaderIsRefusedRatherThanWritten() {
        InsightsSnapshot mismatched = new InsightsSnapshot(
                1L,
                List.of(
                        new InsightsSnapshot.Level(10_000, 90, 20_000, 1),
                        new InsightsSnapshot.Level(60_000, 60, 0, 0)),
                Map.of("cpu.process", List.<double[][]>of(new double[][] {{1.0}})));

        assertThatThrownBy(() -> encoded(mismatched))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cpu.process")
                .hasMessageContaining("levels");
    }

    @Test
    void aFileThatIsNotOursIsRejected() throws IOException {
        byte[] bytes = encoded(snapshot());
        bytes[0] = 'X';

        assertThatThrownBy(() -> decoded(bytes)).isInstanceOf(IOException.class).hasMessageContaining("magic");
    }

    @Test
    void aFutureSchemaIsRejected() throws IOException {
        byte[] bytes = encoded(snapshot());
        bytes[7] = 9; // the schema version's low byte

        assertThatThrownBy(() -> decoded(bytes)).isInstanceOf(IOException.class).hasMessageContaining("schema");
    }

    /** A version 1 file holds the CPU series as ratios and time gauges in seconds; it is refused, never read as percent. */
    @Test
    void aVersionOneFileIsRefused() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(InsightsSnapshotCodec.MAGIC);
            out.writeInt(1);
        }

        assertThatThrownBy(() -> decoded(bytes.toByteArray()))
                .isInstanceOf(IOException.class)
                .hasMessage("unsupported insights snapshot schema 1");
    }

    @Test
    void aTruncatedFileIsRejected() throws IOException {
        byte[] bytes = encoded(snapshot());
        byte[] truncated = new byte[bytes.length - 16];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);

        assertThatThrownBy(() -> decoded(truncated)).isInstanceOf(IOException.class);
    }

    @Test
    void anAbsurdRingSizeIsRejectedBeforeAnythingIsAllocated() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(InsightsSnapshotCodec.MAGIC);
            out.writeInt(InsightsSnapshotCodec.SCHEMA_VERSION);
            out.writeLong(1L);
            out.writeInt(1);
            out.writeLong(10_000);
            out.writeInt(Integer.MAX_VALUE); // size
            out.writeLong(1L);
            out.writeInt(Integer.MAX_VALUE); // count
        }

        assertThatThrownBy(() -> decoded(bytes.toByteArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("implausible");
    }
}
