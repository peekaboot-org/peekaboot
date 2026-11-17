package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InsightsJournalCodecTest {

    private static final long WRITTEN_AT = 1_756_000_000_000L;
    private static final int PAYLOAD_LENGTH = 192;

    private static final InsightsJournalCodec.Header HEADER = new InsightsJournalCodec.Header(
            List.of(new InsightsSnapshot.Level(10_000, 3, 0, 0), new InsightsSnapshot.Level(60_000, 2, 0, 0)),
            List.of("b", "a"));

    private static InsightsSnapshot entries() {
        Map<String, List<double[][]>> series = new LinkedHashMap<>();
        series.put("a", List.of(new double[][] {{1.0, Double.NaN}}, statColumns(10)));
        series.put("b", List.of(new double[][] {{2.0, 3.0}}, statColumns(20)));
        return new InsightsSnapshot(WRITTEN_AT, levels(2, 1), series);
    }

    private static double[][] statColumns(int base) {
        double[][] columns = new double[InsightsSnapshot.STAT_COLUMNS.size()][];
        for (int column = 0; column < columns.length; column++) {
            columns[column] = new double[] {base + column};
        }
        return columns;
    }

    private static List<InsightsSnapshot.Level> levels(int firstCount, int secondCount) {
        return List.of(
                new InsightsSnapshot.Level(10_000, 3, 30_000, firstCount),
                new InsightsSnapshot.Level(60_000, 2, 60_000, secondCount));
    }

    private static byte[] header() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InsightsJournalCodec.writeHeader(new DataOutputStream(bytes), HEADER);
        return bytes.toByteArray();
    }

    private static byte[] record() throws IOException {
        return InsightsJournalCodec.encodeRecord(HEADER, entries());
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }

    private static DataInputStream streamOf(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static InsightsSnapshot decoded(byte[] record) throws IOException {
        return InsightsJournalCodec.readRecord(streamOf(record), HEADER).orElseThrow();
    }

    /** The payload {@link #entries()} encodes to, written by hand from the documented layout. */
    private static byte[] handBuiltPayload(int firstLevelCount) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(WRITTEN_AT);
        out.writeLong(30_000);
        out.writeInt(firstLevelCount);
        out.writeLong(60_000);
        out.writeInt(1);
        for (double base : new double[] {20, 10}) {
            double[] level0 = base == 20 ? new double[] {2.0, 3.0} : new double[] {1.0, Double.NaN};
            for (double value : level0) {
                out.writeDouble(value);
            }
            for (int column = 0; column < 8; column++) {
                out.writeDouble(base + column);
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] framed(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(payload.length);
        out.write(payload);
        CRC32 crc = new CRC32();
        crc.update(payload);
        out.writeInt((int) crc.getValue());
        out.flush();
        return bytes.toByteArray();
    }

    @Test
    void theBytesFollowTheDocumentedLayout() throws IOException {
        ByteArrayOutputStream expectedHeader = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(expectedHeader);
        out.writeInt(0x504B494A);
        out.writeInt(1);
        out.writeInt(2);
        out.writeLong(10_000);
        out.writeInt(3);
        out.writeLong(60_000);
        out.writeInt(2);
        out.writeInt(2);
        out.writeUTF("b");
        out.writeUTF("a");
        out.flush();
        byte[] payload = handBuiltPayload(2);

        assertThat(payload).hasSize(PAYLOAD_LENGTH);
        assertThat(header()).isEqualTo(expectedHeader.toByteArray());
        assertThat(record()).isEqualTo(framed(payload));
    }

    @Test
    void aRecordSurvivesTheRoundTripIncludingItsGaps() throws IOException {
        InsightsSnapshot restored = decoded(record());

        assertThat(restored.writtenAtEpochMs()).isEqualTo(WRITTEN_AT);
        assertThat(restored.levels())
                .containsExactly(
                        new InsightsSnapshot.Level(10_000, 3, 30_000, 2),
                        new InsightsSnapshot.Level(60_000, 2, 60_000, 1));
        assertThat(restored.series().keySet()).containsExactly("b", "a");
        assertThat(restored.series().get("a").get(0)[0]).containsExactly(1.0, Double.NaN);
        assertThat(restored.series().get("b").get(1)).hasDimensions(8, 1);
        assertThat(restored.series().get("b").get(1)[7][0]).isEqualTo(27.0);
    }

    @Test
    void aHeaderSurvivesTheRoundTrip() throws IOException {
        assertThat(InsightsJournalCodec.readHeader(streamOf(header()))).isEqualTo(HEADER);
    }

    @Test
    void aStreamEndingAtARecordBoundaryHoldsNoMoreRecords() throws IOException {
        DataInputStream in = streamOf(concat(header(), record()));
        InsightsJournalCodec.Header header = InsightsJournalCodec.readHeader(in);

        assertThat(InsightsJournalCodec.readRecord(in, header)).isPresent();
        assertThat(InsightsJournalCodec.readRecord(in, header)).isEmpty();
    }

    static Stream<Arguments> cutRecords() {
        return Stream.of(1, 3, 5, 100, 197, 199)
                .map(length -> Arguments.of(Named.of("cut to " + length + " bytes", length)));
    }

    @ParameterizedTest
    @MethodSource("cutRecords")
    void aRecordCutAnywhereIsRefused(int length) throws IOException {
        byte[] cut = Arrays.copyOf(record(), length);

        assertThatThrownBy(() -> decoded(cut)).isInstanceOf(IOException.class);
    }

    @Test
    void aWrongChecksumIsRefused() throws IOException {
        byte[] flipped = record();
        flipped[20] ^= 0x01;

        assertThatThrownBy(() -> decoded(flipped))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void aForeignMagicIsRejected() throws IOException {
        byte[] bytes = header();
        bytes[0] ^= 0x01;

        assertThatThrownBy(() -> InsightsJournalCodec.readHeader(streamOf(bytes)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("magic");
    }

    @Test
    void anUnknownVersionIsRejected() throws IOException {
        byte[] bytes = header();
        bytes[7] = 2;

        assertThatThrownBy(() -> InsightsJournalCodec.readHeader(streamOf(bytes)))
                .isInstanceOf(IOException.class)
                .hasMessage("unsupported insights journal version 2");
    }

    static Stream<Arguments> levelsThatCannotHoldEntries() {
        return Stream.of(
                Arguments.of(Named.of("interval 0", 12), 0L, Long.BYTES),
                Arguments.of(Named.of("a negative interval", 12), -10_000L, Long.BYTES),
                Arguments.of(Named.of("ring size 0", 20), 0L, Integer.BYTES));
    }

    @ParameterizedTest
    @MethodSource("levelsThatCannotHoldEntries")
    void aHeaderLevelThatCannotHoldEntriesIsRefused(int offset, long value, int width) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(header());
        if (width == Long.BYTES) {
            bytes.putLong(offset, value);
        } else {
            bytes.putInt(offset, (int) value);
        }

        assertThatThrownBy(() -> InsightsJournalCodec.readHeader(streamOf(bytes.array())))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("implausible level");
    }

    @Test
    void aCountThatContradictsTheGeometryIsRefused() throws IOException {
        byte[] record = framed(handBuiltPayload(4));

        assertThatThrownBy(() -> decoded(record))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("implausible");
    }

    @Test
    void aLengthBeyondWhatTheGeometryAllowsIsRefusedBeforeAnythingIsAllocated() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(Integer.MAX_VALUE);

        assertThatThrownBy(() -> decoded(bytes.toByteArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("implausible");
    }

    static Stream<Arguments> recordsDifferingFromTheTable() {
        Map<String, List<double[][]>> withoutB = new LinkedHashMap<>(entries().series());
        withoutB.remove("b");
        Map<String, List<double[][]>> withExtraC = new LinkedHashMap<>(entries().series());
        withExtraC.put("c", withExtraC.get("a"));
        return Stream.of(
                Arguments.of(Named.of("without b", withoutB), "b"),
                Arguments.of(Named.of("with an extra c", withExtraC), "c"));
    }

    @ParameterizedTest
    @MethodSource("recordsDifferingFromTheTable")
    void aRecordWhoseSeriesDifferFromTheTableIsRefusedRatherThanWritten(
            Map<String, List<double[][]>> series, String named) {
        InsightsSnapshot differing = new InsightsSnapshot(WRITTEN_AT, levels(2, 1), series);

        assertThatThrownBy(() -> InsightsJournalCodec.encodeRecord(HEADER, differing))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(named);
    }

    @Test
    void aColumnThatContradictsItsCountIsRefusedRatherThanWritten() {
        Map<String, List<double[][]>> series = new LinkedHashMap<>(entries().series());
        series.put("a", List.of(new double[][] {{1.0, 2.0, 3.0}}, statColumns(10)));
        InsightsSnapshot contradicting = new InsightsSnapshot(WRITTEN_AT, levels(2, 1), series);

        assertThatThrownBy(() -> InsightsJournalCodec.encodeRecord(HEADER, contradicting))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("a")
                .hasMessageContaining("level 0");
    }

    @Test
    void aRecordCoveringOtherLevelsThanTheHeaderIsRefusedRatherThanWritten() {
        InsightsSnapshot oneLevel = new InsightsSnapshot(
                WRITTEN_AT,
                levels(2, 1).subList(0, 1),
                Map.of(
                        "a", List.<double[][]>of(new double[][] {{1.0, 2.0}}),
                        "b", List.<double[][]>of(new double[][] {{1.0, 2.0}})));

        assertThatThrownBy(() -> InsightsJournalCodec.encodeRecord(HEADER, oneLevel))
                .isInstanceOf(IOException.class);
    }
}
