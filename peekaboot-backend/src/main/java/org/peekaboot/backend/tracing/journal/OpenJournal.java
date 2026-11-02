package org.peekaboot.backend.tracing.journal;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;

/**
 * The journal being written, and how long it has grown. The size is counted in characters,
 * which is bytes for the ASCII nearly every line is; it only decides when the journal is folded.
 * Writer thread only.
 */
final class OpenJournal implements Closeable {

    private final Writer out;
    private long chars;

    OpenJournal(Writer out) {
        this.out = out;
    }

    long chars() {
        return chars;
    }

    void write(JournalLine line) throws IOException {
        String text = JournalCodec.encode(line);
        out.write(text);
        out.write('\n');
        chars += text.length() + 1;
    }

    void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
