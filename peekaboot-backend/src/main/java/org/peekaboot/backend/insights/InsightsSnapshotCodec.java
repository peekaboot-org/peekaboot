package org.peekaboot.backend.insights;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The insights snapshot's file format, both directions and nothing else.
 *
 * <pre>
 * "PKIN"                     magic
 * int    schemaVersion
 * long   writtenAtEpochMs
 * int    levelCount
 *   per level: long intervalMs, int size, long endEpochMs, int count
 * int    seriesCount
 *   per series: UTF id, then per level, per column: count x double
 * </pre>
 *
 * Every length is checked against a plausibility bound before it is used to allocate:
 * the file is a cache that a crashed write or a stray editor can leave malformed, and
 * a corrupt int must not be able to ask for a multi-gigabyte array.
 */
final class InsightsSnapshotCodec {

    static final int MAGIC = 0x504B494E; // "PKIN"
    static final int SCHEMA_VERSION = 1;

    private static final int MAX_LEVELS = 16;
    private static final int MAX_SERIES = 10_000;
    private static final int MAX_RING_SIZE = 1_000_000;

    private InsightsSnapshotCodec() {}

    /** Magic, version, and the level table - everything needed to judge a file before parsing it. */
    record Header(long writtenAtEpochMs, List<InsightsSnapshot.Level> levels) {}

    /**
     * The body carries no lengths of its own: the reader takes every column's length from
     * the level table, which declares one sample count for all series at once. A column of
     * any other length shifts every following byte, and the only symptom would be the next
     * run reading a double where a series id belongs - so a snapshot that does not match
     * its own header is refused here rather than written.
     */
    static void write(OutputStream stream, InsightsSnapshot snapshot) throws IOException {
        DataOutputStream out = stream instanceof DataOutputStream data ? data : new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeInt(SCHEMA_VERSION);
        out.writeLong(snapshot.writtenAtEpochMs());
        out.writeInt(snapshot.levels().size());
        for (InsightsSnapshot.Level level : snapshot.levels()) {
            out.writeLong(level.intervalMs());
            out.writeInt(level.size());
            out.writeLong(level.endEpochMs());
            out.writeInt(level.count());
        }
        out.writeInt(snapshot.series().size());
        for (Map.Entry<String, List<double[][]>> entry : snapshot.series().entrySet()) {
            out.writeUTF(entry.getKey());
            List<double[][]> byLevel = entry.getValue();
            if (byLevel.size() != snapshot.levels().size()) {
                throw new IOException("insights series " + entry.getKey() + " covers " + byLevel.size()
                        + " levels, not the " + snapshot.levels().size() + " the header declares");
            }
            for (int level = 0; level < byLevel.size(); level++) {
                int count = snapshot.levels().get(level).count();
                for (double[] column : byLevel.get(level)) {
                    if (column.length != count) {
                        throw new IOException("insights series " + entry.getKey() + " holds " + column.length
                                + " values at level " + level + ", not the " + count + " the header declares");
                    }
                    for (double value : column) {
                        out.writeDouble(value);
                    }
                }
            }
        }
        out.flush();
    }

    static Header readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a Peekaboot insights snapshot (bad magic)");
        }
        int version = in.readInt();
        if (version != SCHEMA_VERSION) {
            throw new IOException("unsupported insights snapshot schema " + version);
        }
        long writtenAtEpochMs = in.readLong();
        int levelCount = bounded(in.readInt(), MAX_LEVELS, "level count");
        List<InsightsSnapshot.Level> levels = new ArrayList<>(levelCount);
        for (int level = 0; level < levelCount; level++) {
            long intervalMs = in.readLong();
            int size = bounded(in.readInt(), MAX_RING_SIZE, "ring size");
            long endEpochMs = in.readLong();
            int count = bounded(in.readInt(), size, "sample count");
            levels.add(new InsightsSnapshot.Level(intervalMs, size, endEpochMs, count));
        }
        return new Header(writtenAtEpochMs, levels);
    }

    static InsightsSnapshot readBody(DataInputStream in, Header header) throws IOException {
        int seriesCount = bounded(in.readInt(), MAX_SERIES, "series count");
        Map<String, List<double[][]>> series = new LinkedHashMap<>(seriesCount);
        for (int i = 0; i < seriesCount; i++) {
            String id = in.readUTF();
            List<double[][]> byLevel = new ArrayList<>(header.levels().size());
            for (int level = 0; level < header.levels().size(); level++) {
                int count = header.levels().get(level).count();
                int columnCount = level == 0 ? 1 : InsightsSnapshot.STAT_COLUMNS.size();
                double[][] columns = new double[columnCount][];
                for (int column = 0; column < columnCount; column++) {
                    columns[column] = readColumn(in, count);
                }
                byLevel.add(columns);
            }
            series.put(id, byLevel);
        }
        return new InsightsSnapshot(header.writtenAtEpochMs(), header.levels(), series);
    }

    private static double[] readColumn(DataInputStream in, int count) throws IOException {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readDouble();
        }
        return values;
    }

    private static int bounded(int value, int max, String what) throws IOException {
        if (value < 0 || value > max) {
            throw new IOException("implausible " + what + " in insights snapshot: " + value);
        }
        return value;
    }
}
