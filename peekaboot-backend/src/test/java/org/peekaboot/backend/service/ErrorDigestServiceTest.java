package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.errors.ErrorDigestEntry;
import org.peekaboot.backend.domain.errors.ErrorDigestParameters;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.testsupport.FailedTraces;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.digest.DigestedError;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceInput;

class ErrorDigestServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    /** A failure on {@code /carts/{id}} whose latest trace the store no longer holds. */
    private static final DigestedError EVICTED = new DigestedError(
            null, "/carts/{id}", null, 4, T0.minusSeconds(60), T0.minusSeconds(30), null, "evicted", List.of());

    private final InMemoryTraceStore store = TraceStores.withDefaults();
    private final ErrorDigest digest = new ErrorDigest();
    private final ErrorDigestService service = new ErrorDigestService(digest, store);

    /**
     * {@link #EVICTED} restored from the last run, then a failed request the store still holds, recorded
     * the way a live one is. The digest holds them in the reverse of their lastSeen order.
     */
    private void recordBoth() {
        digest.restore(List.of(EVICTED));
        FailedTraces.recorder(store, digest)
                .liveInputs()
                .accept(new TraceInput.SpanArrived(FailedTraces.root("held", T0, 500)));
    }

    private List<ErrorDigestEntry> list(String since, String route) {
        return service.list(new ErrorDigestParameters(since, route)).errors();
    }

    @Test
    void listsEveryEntryLatestFirstSayingWhetherItsTraceIsStillHeld() {
        recordBoth();

        assertThat(list(null, null))
                .extracting(ErrorDigestEntry::lastTraceId, ErrorDigestEntry::traceAvailable, ErrorDigestEntry::count)
                .containsExactly(tuple("held", true, 1), tuple("evicted", false, 4));
    }

    @Test
    void sinceKeepsTheEntriesLastSeenAtOrAfterIt() {
        recordBoth();

        assertThat(list("2026-11-06T09:59:30Z", null))
                .extracting(ErrorDigestEntry::lastTraceId)
                .containsExactly("held", "evicted");
        assertThat(list("2026-11-06T09:59:31Z", null))
                .extracting(ErrorDigestEntry::lastTraceId)
                .containsExactly("held");
    }

    @Test
    void routeKeepsTheEntriesOfThatRoutePattern() {
        recordBoth();

        assertThat(list(null, "/carts/{id}"))
                .extracting(ErrorDigestEntry::lastTraceId)
                .containsExactly("evicted");
        assertThat(list(null, "/carts/1")).isEmpty();
    }

    @Test
    void aSinceThatIsNoInstantIsRefusedEvenWhileTracingIsOff() {
        ErrorDigestService tracingOff = new ErrorDigestService(null, null);

        assertThatThrownBy(() -> tracingOff.list(new ErrorDigestParameters("yesterday", null)))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo("since"));
    }

    @Test
    void withTracingOffTheDigestListsNothing() {
        assertThat(new ErrorDigestService(null, null)
                        .list(new ErrorDigestParameters(null, null))
                        .errors())
                .isEmpty();
    }
}
