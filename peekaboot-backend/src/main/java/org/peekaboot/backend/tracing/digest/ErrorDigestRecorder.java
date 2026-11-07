package org.peekaboot.backend.tracing.digest;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.ListingRoots;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceInputs;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a live trace in the {@link ErrorDigest} once it is complete and failed. Only live inputs pass
 * through here: the trace journal replays its files into the store directly, so a restored trace,
 * counted by the run that captured it, never counts again. A request that calls its own application
 * is digested under the inner request's route and cause when the inner one fails first: the store
 * counts the trace complete from that moment. The trace turns incomplete once the application's own
 * client span is stored and complete again with the outer root; {@link ErrorDigest#claim} keeps that
 * second time from counting, so the outer route never takes it over.
 */
public final class ErrorDigestRecorder {

    private static final Logger log = LoggerFactory.getLogger(ErrorDigestRecorder.class);

    private final TraceStore store;
    private final ErrorDigest digest;
    private final TraceResources resources;
    private final AtomicBoolean warned = new AtomicBoolean();

    public ErrorDigestRecorder(TraceStore store, ErrorDigest digest, TraceResources resources) {
        this.store = store;
        this.digest = digest;
        this.resources = resources;
    }

    /**
     * Applies each input to the store, then records the trace where this input made it complete and
     * failed. A trace that was both before the input, such as one restored from the last run, was
     * counted when it got there.
     */
    public TraceInputs liveInputs() {
        return input -> {
            String traceId = decisiveTraceId(input);
            boolean countedAlready = traceId == null || wasCompleteAndFailed(traceId);
            input.applyTo(store);
            if (!countedAlready) {
                recordIfNowFailed(traceId);
            }
        };
    }

    /**
     * The trace an input can complete or fail, or null for any other input: a span without a parent or
     * continuing a remote caller, a failing span, or an ERROR line. A server span answering a 5xx is
     * usually one of the first two. Under a local parent, such as a span a host filter opens, it is
     * neither, and its trace still counts once that root arrives.
     */
    @Nullable
    private static String decisiveTraceId(TraceInput input) {
        return switch (input) {
            case TraceInput.SpanArrived arrived
            when mayCompleteOrFail(arrived.span()) -> arrived.span().traceId();
            case TraceInput.LogCaptured captured
            when captured.log().isError() -> captured.log().traceId();
            default -> null;
        };
    }

    private static boolean mayCompleteOrFail(SpanData span) {
        return span.parentId() == null || span.remoteParent() || span.hasError();
    }

    /** A lookup that throws counts as already counted, so nothing is recorded for it. */
    private boolean wasCompleteAndFailed(String traceId) {
        try {
            return store.getTrace(traceId)
                    .map(TraceDataBundle::listingRoots)
                    .filter(facts -> facts.complete() && facts.failed())
                    .isPresent();
        } catch (RuntimeException e) {
            warnOnce(traceId, e);
            return true;
        }
    }

    /** A failure here costs the digest one entry, never the input its journal line or the exporter its batch. */
    private void recordIfNowFailed(String traceId) {
        try {
            store.getTrace(traceId).ifPresent(this::recordIfFailed);
        } catch (RuntimeException e) {
            warnOnce(traceId, e);
        }
    }

    /** The summary is mapped once per failed trace, on the thread that applied the input. */
    private void recordIfFailed(TraceDataBundle bundle) {
        ListingRoots facts = bundle.listingRoots();
        if (facts.complete() && facts.failed() && digest.claim(bundle.traceId())) {
            digest.record(
                    resources.row(bundle, null, Set.of(), TraceView.SUMMARY).summary());
        }
    }

    private void warnOnce(String traceId, RuntimeException e) {
        if (warned.compareAndSet(false, true)) {
            log.warn(
                    "Peekaboot errors: cannot record trace {} in the error digest ({}); the digest misses it",
                    traceId,
                    e.toString());
        }
    }
}
