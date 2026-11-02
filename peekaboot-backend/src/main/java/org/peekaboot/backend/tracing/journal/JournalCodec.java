package org.peekaboot.backend.tracing.journal;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceInput;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/** The trace journal's line format, both directions and nothing else. */
final class JournalCodec {

    /** Raised with every change to a line's shape; a file of another version is discarded whole. */
    static final int VERSION = 1;

    /** Its own mapper, so the host application's Jackson configuration never reaches this file's format. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final ObjectWriter WRITER = MAPPER.writerFor(JournalLine.class);

    private JournalCodec() {}

    static String encode(JournalLine line) {
        return WRITER.writeValueAsString(line);
    }

    /** The line, or null where this version cannot use it: damaged, cut short, of an unknown type, or missing what its type needs. */
    @Nullable
    static JournalLine decode(String text) {
        try {
            return MAPPER.readValue(text, JournalLine.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** The line an input is journalled as; the run reporting ready is the run line its inputs follow. */
    static JournalLine lineOf(TraceInput input) {
        return switch (input) {
            case TraceInput.SpanArrived arrived -> new JournalLine.SpanLine(arrived.span());
            case TraceInput.LogCaptured captured -> JournalLine.LogLine.of(captured.log());
            case TraceInput.RequestCompleted completed -> new JournalLine.RequestLine(completed.request());
            case TraceInput.TraceDiscarded discarded -> new JournalLine.DiscardLine(discarded.traceId());
            case TraceInput.RunStarted started -> new JournalLine.RunLine(started.startedAt());
        };
    }
}
