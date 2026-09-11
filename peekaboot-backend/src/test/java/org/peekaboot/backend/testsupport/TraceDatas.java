package org.peekaboot.backend.testsupport;

import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

/**
 * Builds {@link TraceData} the way the store does: the spans go through a
 * {@link TraceDataBundle} with no cap, so the snapshot carries the root the bundle chooses and
 * the spans in creation order, whatever order a test lists them in.
 */
public final class TraceDatas {

    private TraceDatas() {}

    public static TraceData of(String traceId, SpanData... spans) {
        TraceDataBundle bundle = new TraceDataBundle(traceId);
        for (SpanData span : spans) {
            bundle.addSpan(span, Integer.MAX_VALUE);
        }
        return bundle.snapshot();
    }
}
