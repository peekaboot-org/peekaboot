package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.event.SpanData;

class DuplicateFoldsTest {

    private static final int CAP = 10;

    private final DuplicateFolds folds = new DuplicateFolds();
    private final Map<String, SpanData> stored = new LinkedHashMap<>();

    /**
     * The table gains one entry per fold. 500 real and duplicate pairs against a cap of 10 would
     * leave close to 500 entries if evicting a survivor did not take its redirects along.
     */
    @Test
    void theRedirectTableIsPrunedAsSurvivorsAreEvicted() {
        for (int i = 0; i < 500; i++) {
            String realId = "real" + i;
            add(jdbcDuplicate("dup" + i, realId, "SELECT " + i).build());
            add(jdbcQuery(realId, "SELECT " + i).build());
        }

        assertThat(stored).hasSize(CAP);
        assertThat(folds.redirectCount()).isLessThanOrEqualTo(CAP * 2);
    }

    /**
     * Three spans that match pairwise, nested three deep and arriving innermost first: the
     * innermost folds into the middle one, which then folds into the real span. The middle one
     * was never stored, so it is never evicted, and a redirect still keyed on it would outlive
     * the real span. Double instrumentation never nests three deep, but the bound has to hold.
     */
    @Test
    void aChainedRedirectIsPrunedWithItsFinalSurvivor() {
        for (int i = 0; i < 500; i++) {
            String survivorId = "survivor" + i;
            String middleId = "middle" + i;
            add(jdbcQuery("innermost" + i, "SELECT " + i)
                    .parent(middleId)
                    .tag("peer.service", "ds-inner")
                    .build());
            add(jdbcQuery(middleId, "SELECT " + i)
                    .parent(survivorId)
                    .tag("peer.service", "ds-mid")
                    .build());
            add(jdbcQuery(survivorId, "SELECT " + i).build());
        }

        assertThat(stored).hasSize(CAP);
        assertThat(folds.redirectCount()).isLessThanOrEqualTo(CAP * 2);
    }

    /** What {@code TraceDataBundle.addSpan} does with an arriving span, async membership aside. */
    private void add(SpanData span) {
        if (folds.foldIntoStoredParent(span, stored) == null) {
            stored.put(span.spanId(), span);
            folds.stored(span);
            folds.absorbDuplicateChildren(span, stored);
        }
        Iterator<SpanData> oldestFirst = stored.values().iterator();
        while (stored.size() > CAP) {
            SpanData evicted = oldestFirst.next();
            oldestFirst.remove();
            folds.evicted(evicted);
        }
    }
}
