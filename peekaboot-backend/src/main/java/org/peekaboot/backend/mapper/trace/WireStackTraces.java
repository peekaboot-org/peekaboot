package org.peekaboot.backend.mapper.trace;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;
import org.peekaboot.backend.stacktrace.StackTraceRules;

/**
 * A captured stack trace as the wire carries it, for a log and a failed span alike. Masked whole
 * before folding, so a secret spanning lines (a PEM key) still matches and the ranges index the
 * masked lines.
 */
final class WireStackTraces {

    record WireStackTrace(@Nullable String text, List<Range> hiddenFrames, List<Range> applicationFrames) {
        static final WireStackTrace NONE = new WireStackTrace(null, List.of(), List.of());
    }

    private final MaskingEngine maskingEngine;
    private final StackTraceRules stackTraceRules;

    WireStackTraces(MaskingEngine maskingEngine, StackTraceRules stackTraceRules) {
        this.maskingEngine = maskingEngine;
        this.stackTraceRules = stackTraceRules;
    }

    WireStackTrace of(@Nullable String captured) {
        if (captured == null) {
            return WireStackTrace.NONE;
        }
        FoldedTrace folded = stackTraceRules.fold(maskingEngine.maskValue(captured));
        // The ranges index into folded.lines(), not the raw captured string: String.lines() splits
        // on a lone \r where the browser's split('\n') would not, so sending anything else back
        // would leave the two disagreeing about which line a given index names.
        return new WireStackTrace(String.join("\n", folded.lines()), folded.hidden(), folded.applicationFrames());
    }
}
