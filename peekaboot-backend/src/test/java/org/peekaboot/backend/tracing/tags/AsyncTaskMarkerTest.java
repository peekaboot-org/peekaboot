package org.peekaboot.backend.tracing.tags;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AsyncTaskMarkerTest {

    @Test
    void marksTagsCarryingTheKeyWhateverItsValue() {
        assertThat(AsyncTaskMarker.isMarked(Map.of(AsyncTaskMarker.TAG_KEY, "anything")))
                .isTrue();
    }

    @Test
    void doesNotMarkTagsWithoutTheKey() {
        assertThat(AsyncTaskMarker.isMarked(Map.of(AsyncTaskMarker.THREAD_TAG_KEY, "pool-1")))
                .isFalse();
    }

    @Test
    void doesNotMarkASpanWithNoTags() {
        assertThat(AsyncTaskMarker.isMarked(null)).isFalse();
    }
}
