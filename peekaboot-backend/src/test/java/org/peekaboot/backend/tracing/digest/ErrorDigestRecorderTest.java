package org.peekaboot.backend.tracing.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.FailedTraces;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceBucket;
import org.peekaboot.backend.tracing.store.TraceDataBundle;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceInputs;
import org.peekaboot.testsupport.LogCapture;

class ErrorDigestRecorderTest {

    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");

    private final InMemoryTraceStore store = TraceStores.withDefaults();
    private final ErrorDigest digest = new ErrorDigest();
    private final TraceInputs live = FailedTraces.recorder(store, digest).liveInputs();

    private void arrive(SpanData span) {
        live.accept(new TraceInput.SpanArrived(span));
    }

    @Test
    void aFailedTraceIsRecordedOnceItsRootArrives() {
        arrive(FailedTraces.handler("t1", START, "out of stock", 42));
        assertThat(digest.entries())
                .as("before the root, the trace is still running")
                .isEmpty();

        arrive(FailedTraces.root("t1", START, 500));

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo(FailedTraces.TYPE);
            assertThat(entry.route()).isEqualTo(FailedTraces.ROUTE);
            assertThat(entry.applicationFrame()).isEqualTo("org.acme.Shop.buy(Shop.java)");
            assertThat(entry.count()).isEqualTo(1);
            assertThat(entry.lastMessage()).isEqualTo("out of stock");
            assertThat(entry.lastTraceId()).isEqualTo("t1");
            assertThat(entry.firstSeen()).isEqualTo(START);
        });
    }

    /**
     * A request continuing another service's trace has a remote parent and no parentless span; its
     * server span roots it.
     */
    @Test
    void aRequestContinuingARemoteCallersTraceIsRecordedOnceItsSpanArrives() {
        arrive(FailedTraces.handler("t1", START, "out of stock", 42));

        arrive(span("root")
                .in("t1")
                .remoteParent("caller")
                .kind(Span.Kind.SERVER)
                .at(START, Duration.ofMillis(10))
                .tag("http.route", FailedTraces.ROUTE)
                .tag("http.response.status_code", "500")
                .build());

        assertThat(digest.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.lastTraceId()).isEqualTo("t1"));
    }

    @Test
    void aTraceThatSucceedsIsNotRecorded() {
        arrive(FailedTraces.root("t1", START, 200));

        assertThat(digest.entries()).isEmpty();
    }

    @Test
    void aTraceCountsOnceWhateverArrivesForItAfterwards() {
        arrive(FailedTraces.handler("t1", START, "out of stock", 42));
        arrive(FailedTraces.root("t1", START, 500));

        live.accept(
                new TraceInput.LogCaptured(log("t1").at("ERROR").saying("again").build()));
        arrive(span("late")
                .in("t1")
                .parent("root")
                .at(START, Duration.ofMillis(1))
                .exception("x.Late", "late")
                .build());

        assertThat(digest.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.count()).isEqualTo(1));
    }

    /**
     * Two inputs that each find the trace not yet failed, then both see it complete and failed, count it
     * once. The store below lets the root through while the failing span is on its way in, the
     * interleaving two exporter threads can produce.
     */
    @Test
    void twoInputsRacingToFailATraceCountItOnce() {
        AtomicReference<TraceInputs> racing = new AtomicReference<>();
        InMemoryTraceStore interleaving = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public void addSpan(SpanData span) {
                if ("handler".equals(span.spanId())) {
                    racing.get().accept(new TraceInput.SpanArrived(FailedTraces.root("t1", START, 500)));
                }
                super.addSpan(span);
            }
        };
        racing.set(FailedTraces.recorder(interleaving, digest).liveInputs());

        racing.get().accept(new TraceInput.SpanArrived(FailedTraces.handler("t1", START, "out of stock", 42)));

        assertThat(digest.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.count()).isEqualTo(1));
    }

    /** A trace restored complete and failed was counted in the run that captured it; a late line adds nothing. */
    @Test
    void aTraceAlreadyCompleteAndFailedBeforeTheInputIsNotRecordedAgain() {
        new TraceInput.SpanArrived(FailedTraces.root("t1", START, 500)).applyTo(store);

        live.accept(
                new TraceInput.LogCaptured(log("t1").at("ERROR").saying("late").build()));

        assertThat(digest.entries()).isEmpty();
    }

    /** An async task's failure arrives after the request's root and still fails the trace. */
    @Test
    void aFailureArrivingAfterTheRootCountsTheTrace() {
        arrive(FailedTraces.root("t1", START, 200));

        arrive(FailedTraces.handler("t1", START, "async task failed", 42));

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.lastMessage()).isEqualTo("async task failed");
            assertThat(entry.route()).isEqualTo(FailedTraces.ROUTE);
        });
    }

    @Test
    void anErrorLineAfterTheRootCountsTheTraceUnderItsMessage() {
        arrive(FailedTraces.root("t1", START, 200));

        live.accept(new TraceInput.LogCaptured(
                log("t1").at("ERROR").saying("payment declined").build()));

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isNull();
            assertThat(entry.applicationFrame()).isNull();
            assertThat(entry.lastMessage()).isEqualTo("payment declined");
        });
    }

    /** The digest takes the summary the API serves, so a secret an ERROR line echoes is kept masked. */
    @Test
    void anErrorLinesMessageIsRecordedMasked() {
        arrive(FailedTraces.root("t1", START, 200));

        live.accept(new TraceInput.LogCaptured(log("t1")
                .at("ERROR")
                .saying("calling https://admin:hunter2@example.com/api failed")
                .build()));

        assertThat(digest.entries())
                .singleElement()
                .satisfies(entry ->
                        assertThat(entry.lastMessage()).isEqualTo("calling https://******@example.com/api failed"));
    }

    /** An untraced ERROR line names no trace to record, and still reaches the store's untraced ring. */
    @Test
    void anErrorLineOutsideAnyTraceRecordsNothing() {
        live.accept(new TraceInput.LogCaptured(
                untraced().at("ERROR").saying("startup failed").build()));

        assertThat(digest.entries()).isEmpty();
        assertThat(store.untracedLogs()).hasSize(1);
    }

    /** Only an input that can complete or fail a trace looks it up, so any other costs its thread nothing more. */
    @Test
    void anInputThatCanNeitherCompleteNorFailATraceLooksNothingUp() {
        AtomicInteger lookups = new AtomicInteger();
        InMemoryTraceStore counting = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public Optional<TraceDataBundle> getTrace(String traceId) {
                lookups.incrementAndGet();
                return super.getTrace(traceId);
            }
        };
        TraceInputs countingLive = FailedTraces.recorder(counting, digest).liveInputs();

        countingLive.accept(new TraceInput.SpanArrived(span("query")
                .in("t1")
                .parent("root")
                .at(START, Duration.ofMillis(1))
                .build()));
        countingLive.accept(
                new TraceInput.LogCaptured(log("t1").at("INFO").saying("fine").build()));
        countingLive.accept(new TraceInput.LogCaptured(
                untraced().at("ERROR").saying("startup failed").build()));

        assertThat(lookups).hasValue(0);
    }

    /**
     * A request that calls its own application is complete while the inner request roots it, incomplete
     * once the application's client span is stored, and complete again with the outer root. It counts
     * once, under the inner request, which failed first.
     */
    @Test
    void aRequestCallingItsOwnApplicationCountsOnceUnderTheInnerRequest() {
        arrive(span("inner")
                .in("t1")
                .remoteParent("client")
                .kind(Span.Kind.SERVER)
                .at(START, Duration.ofMillis(5))
                .tag("http.route", "/inner")
                .tag("http.response.status_code", "500")
                .build());
        arrive(span("client")
                .in("t1")
                .parent("outer")
                .kind(Span.Kind.CLIENT)
                .at(START, Duration.ofMillis(6))
                .build());
        arrive(span("outer")
                .in("t1")
                .kind(Span.Kind.SERVER)
                .at(START, Duration.ofMillis(10))
                .tag("http.route", FailedTraces.ROUTE)
                .tag("http.response.status_code", "500")
                .build());

        assertThat(digest.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.route()).isEqualTo("/inner");
            assertThat(entry.count()).isEqualTo(1);
        });
    }

    @Test
    void theEntryNamesTheRunTheTraceCameFrom() {
        Instant run = START.minusSeconds(60);
        store.markRunStarted(run);

        arrive(FailedTraces.root("t1", START, 500));

        assertThat(digest.entries())
                .singleElement()
                .satisfies(entry -> assertThat(entry.runs()).containsExactly(run));
    }

    /**
     * A store lookup that throws costs the digest its entry; the input still reaches the store, and one
     * WARN says so.
     */
    @Test
    void aLookupThatThrowsCostsOnlyTheDigestEntry() {
        InMemoryTraceStore failing = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public Optional<TraceDataBundle> getTrace(String traceId) {
                throw new IllegalStateException("lookup failed");
            }
        };
        TraceInputs failingLive = FailedTraces.recorder(failing, digest).liveInputs();

        try (LogCapture capture = LogCapture.attach(ErrorDigestRecorder.class)) {
            failingLive.accept(new TraceInput.SpanArrived(FailedTraces.root("t1", START, 500)));
            failingLive.accept(new TraceInput.SpanArrived(FailedTraces.root("t2", START, 500)));

            assertThat(capture.appender().list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .startsWith("Peekaboot errors: cannot record trace t1 in the error digest"));
        }
        assertThat(failing.getTraceCount(TraceBucket.ALL)).isEqualTo(2);
        assertThat(digest.entries()).isEmpty();
    }

    /** A lookup that throws once the input is stored costs the digest its entry the same way. */
    @Test
    void aLookupThatThrowsOnceTheTraceIsStoredCostsOnlyTheDigestEntry() {
        InMemoryTraceStore failing = new InMemoryTraceStore(new PeekabootTracingProperties()) {
            @Override
            public Optional<TraceDataBundle> getTrace(String traceId) {
                if (super.getTrace(traceId).isPresent()) {
                    throw new IllegalStateException("lookup failed");
                }
                return Optional.empty();
            }
        };
        TraceInputs failingLive = FailedTraces.recorder(failing, digest).liveInputs();

        try (LogCapture capture = LogCapture.attach(ErrorDigestRecorder.class)) {
            failingLive.accept(new TraceInput.SpanArrived(FailedTraces.root("t1", START, 500)));

            assertThat(capture.appender().list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage())
                            .startsWith("Peekaboot errors: cannot record trace t1 in the error digest"));
        }
        assertThat(failing.getTraceCount(TraceBucket.ALL)).isEqualTo(1);
        assertThat(digest.entries()).isEmpty();
    }
}
