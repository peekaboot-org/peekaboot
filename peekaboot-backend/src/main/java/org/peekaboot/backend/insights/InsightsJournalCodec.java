package org.peekaboot.backend.insights;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;
import org.peekaboot.backend.insights.config.InsightsProperties;

/**
 * The insights journal's file format, both directions and nothing else. The header fixes
 * the series order of every record that follows it.
 *
 * <pre>
 * header:  int MAGIC "PKIJ", int VERSION, int levelCount, per level: long intervalMs, int size,
 *          int seriesCount, per series: UTF id
 * record:  int length, payload[length], int crc32(payload)
 * payload: long writtenAtEpochMs, per level: long endEpochMs, int count,
 *          per series in table order, per level, per column: count x double
 * </pre>
 *
 * Reads are bounded like {@link InsightsSnapshotCodec}'s. A record cut by a kill or altered on
 * disk is refused whole: its length is checked before the payload is allocated, and its
 * checksum before the payload is decoded.
 */
final class InsightsJournalCodec {

    static final int MAGIC = 0x504B494A; // "PKIJ"
    static final int VERSION = 1;

    private static final int LENGTH_BYTES = 4;
    private static final int RECORD_FIXED_BYTES = Long.BYTES;
    private static final int LEVEL_BYTES = Long.BYTES + Integer.BYTES;

    private InsightsJournalCodec() {}

    /** The level geometry (end and count 0) and the series table every record follows. */
    record Header(List<InsightsSnapshot.Level> geometry, List<String> seriesIds) {}

    static void writeHeader(DataOutputStream out, Header header) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(header.geometry().size());
        for (InsightsSnapshot.Level level : header.geometry()) {
            out.writeLong(level.intervalMs());
            out.writeInt(level.size());
        }
        out.writeInt(header.seriesIds().size());
        for (String id : header.seriesIds()) {
            out.writeUTF(id);
        }
        out.flush();
    }

    static Header readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a Peekaboot insights journal (bad magic)");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported insights journal version " + version);
        }
        int levelCount = InsightsSnapshotCodec.bounded(in.readInt(), InsightsProperties.MAX_LEVELS, "level count");
        List<InsightsSnapshot.Level> geometry = new ArrayList<>(levelCount);
        for (int level = 0; level < levelCount; level++) {
            long intervalMs = in.readLong();
            int size = InsightsSnapshotCodec.bounded(in.readInt(), InsightsProperties.MAX_LEVEL_SIZE, "ring size");
            // The header carries no checksum, and the replay divides by the interval and sizes rings by the size.
            if (intervalMs < 1 || size < 1) {
                throw new IOException(
                        "implausible level in insights file: interval " + intervalMs + " ms, size " + size);
            }
            geometry.add(new InsightsSnapshot.Level(intervalMs, size, 0, 0));
        }
        int seriesCount = InsightsSnapshotCodec.bounded(in.readInt(), InsightsSnapshotCodec.MAX_SERIES, "series count");
        List<String> seriesIds = new ArrayList<>(seriesCount);
        for (int i = 0; i < seriesCount; i++) {
            seriesIds.add(in.readUTF());
        }
        return new Header(geometry, seriesIds);
    }

    /**
     * Like the snapshot, a record carries no lengths of its own, so one that does not match
     * the header is refused here rather than written: it would shift every byte after it.
     */
    static byte[] encodeRecord(Header header, InsightsSnapshot entries) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(payloadBytes);
        writePayload(payload, header, entries);
        payload.flush();
        byte[] bytes = payloadBytes.toByteArray();

        ByteArrayOutputStream framed = new ByteArrayOutputStream(LENGTH_BYTES + bytes.length + Integer.BYTES);
        DataOutputStream out = new DataOutputStream(framed);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.writeInt(checksum(bytes));
        out.flush();
        return framed.toByteArray();
    }

    static Optional<InsightsSnapshot> readRecord(DataInputStream in, Header header) throws IOException {
        int first = in.read();
        if (first < 0) {
            return Optional.empty();
        }
        int length =
                (first << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (length < minimumPayloadBytes(header) || length > maximumPayloadBytes(header)) {
            throw new IOException("implausible record length in insights file: " + length);
        }
        // Read as far as the bytes go: the header that bounds the length carries no checksum.
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("insights journal record cut after " + payload.length + " of " + length + " bytes");
        }
        if (in.readInt() != checksum(payload)) {
            throw new IOException("insights journal record fails its checksum");
        }
        DataInputStream entries = new DataInputStream(new ByteArrayInputStream(payload));
        InsightsSnapshot record = readPayload(entries, header);
        if (entries.available() > 0) {
            throw new IOException("insights journal record holds " + entries.available() + " bytes after its entries");
        }
        return Optional.of(record);
    }

    private static void writePayload(DataOutputStream out, Header header, InsightsSnapshot entries) throws IOException {
        requireMatches(header, entries);
        out.writeLong(entries.writtenAtEpochMs());
        for (InsightsSnapshot.Level level : entries.levels()) {
            out.writeLong(level.endEpochMs());
            out.writeInt(level.count());
        }
        for (String id : header.seriesIds()) {
            for (double[][] columns : entries.series().get(id)) {
                for (double[] column : columns) {
                    for (double value : column) {
                        out.writeDouble(value);
                    }
                }
            }
        }
    }

    /**
     * A record that does not match its header would shift every byte after it, so it is refused
     * before any is written.
     */
    private static void requireMatches(Header header, InsightsSnapshot entries) throws IOException {
        List<InsightsSnapshot.Level> levels = entries.levels();
        if (levels.size() != header.geometry().size()) {
            throw new IOException("insights record covers " + levels.size() + " levels, not the "
                    + header.geometry().size() + " the journal header declares");
        }
        for (String id : entries.series().keySet()) {
            if (!header.seriesIds().contains(id)) {
                throw new IOException("insights record holds series " + id + ", which the journal header lacks");
            }
        }
        for (String id : header.seriesIds()) {
            List<double[][]> byLevel = entries.series().get(id);
            if (byLevel == null) {
                throw new IOException("insights record lacks series " + id + ", which the journal header declares");
            }
            requireColumnsMatch(id, byLevel, levels);
        }
    }

    private static void requireColumnsMatch(String id, List<double[][]> byLevel, List<InsightsSnapshot.Level> levels)
            throws IOException {
        if (byLevel.size() != levels.size()) {
            throw new IOException("insights series " + id + " covers " + byLevel.size() + " levels, not the "
                    + levels.size() + " the record declares");
        }
        for (int level = 0; level < byLevel.size(); level++) {
            int columns = byLevel.get(level).length;
            if (columns != columnsAt(level)) {
                throw new IOException("insights series " + id + " holds " + columns + " columns at level " + level
                        + ", not the " + columnsAt(level) + " the format declares");
            }
            int count = levels.get(level).count();
            for (double[] column : byLevel.get(level)) {
                if (column.length != count) {
                    throw new IOException("insights series " + id + " holds " + column.length + " values at level "
                            + level + ", not the " + count + " the record declares");
                }
            }
        }
    }

    private static InsightsSnapshot readPayload(DataInputStream in, Header header) throws IOException {
        long writtenAtEpochMs = in.readLong();
        List<InsightsSnapshot.Level> levels = new ArrayList<>(header.geometry().size());
        for (InsightsSnapshot.Level geometry : header.geometry()) {
            long endEpochMs = in.readLong();
            int count = InsightsSnapshotCodec.bounded(in.readInt(), geometry.size(), "sample count");
            levels.add(new InsightsSnapshot.Level(geometry.intervalMs(), geometry.size(), endEpochMs, count));
        }
        Map<String, List<double[][]>> series =
                new LinkedHashMap<>(header.seriesIds().size());
        for (String id : header.seriesIds()) {
            List<double[][]> byLevel = new ArrayList<>(levels.size());
            for (int level = 0; level < levels.size(); level++) {
                int count = levels.get(level).count();
                double[][] columns = new double[columnsAt(level)][];
                for (int column = 0; column < columns.length; column++) {
                    columns[column] = InsightsSnapshotCodec.readColumn(in, count);
                }
                byLevel.add(columns);
            }
            series.put(id, byLevel);
        }
        return new InsightsSnapshot(writtenAtEpochMs, levels, series);
    }

    private static int columnsAt(int level) {
        return level == 0 ? 1 : InsightsSnapshot.STAT_COLUMNS.size();
    }

    private static long minimumPayloadBytes(Header header) {
        return RECORD_FIXED_BYTES + (long) header.geometry().size() * LEVEL_BYTES;
    }

    private static long maximumPayloadBytes(Header header) {
        long valuesPerSeries = 0;
        for (int level = 0; level < header.geometry().size(); level++) {
            valuesPerSeries += (long) header.geometry().get(level).size() * columnsAt(level);
        }
        return minimumPayloadBytes(header) + header.seriesIds().size() * valuesPerSeries * Double.BYTES;
    }

    private static int checksum(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        return (int) crc.getValue();
    }
}
