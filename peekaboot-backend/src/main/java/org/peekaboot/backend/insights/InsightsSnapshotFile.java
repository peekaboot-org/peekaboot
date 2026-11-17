package org.peekaboot.backend.insights;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.peekaboot.backend.storage.OwnerOnlyFiles;

/** Reads and replaces the insights snapshot file; the bytes themselves are {@link InsightsSnapshotCodec}'s. */
final class InsightsSnapshotFile {

    private InsightsSnapshotFile() {}

    static InsightsSnapshot read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return InsightsSnapshotCodec.readBody(in, InsightsSnapshotCodec.readHeader(in));
        }
    }

    /** The bytes are counted as they are written, so a failing size lookup cannot fail a snapshot that is in place. */
    static long replace(Path file, InsightsSnapshot snapshot) throws IOException {
        long[] written = new long[1];
        OwnerOnlyFiles.replaceAtomically(file, stream -> {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream))) {
                InsightsSnapshotCodec.write(out, snapshot);
                written[0] = out.size();
            }
        });
        return written[0];
    }
}
