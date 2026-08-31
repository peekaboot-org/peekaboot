package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
