package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.problem.ApiProblemException;

class TimeWindowTest {

    private static final Instant SINCE = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-11-01T11:00:00Z");

    @Test
    void anOpenWindowHoldsEveryInstantAndTheMissingOne() {
        TimeWindow open = TimeWindow.of(null, " ");

        assertThat(open.isOpen()).isTrue();
        assertThat(open.contains(SINCE)).isTrue();
        assertThat(open.contains(null)).isTrue();
    }

    @Test
    void sinceIsInAndUntilIsOut() {
        TimeWindow window = TimeWindow.of("2026-11-01T10:00:00Z", "2026-11-01T11:00:00Z");

        assertThat(window.contains(SINCE.minusNanos(1))).isFalse();
        assertThat(window.contains(SINCE)).isTrue();
        assertThat(window.contains(UNTIL.minusNanos(1))).isTrue();
        assertThat(window.contains(UNTIL)).isFalse();
    }

    @Test
    void anOpenSideReachesAsFarAsItGoes() {
        assertThat(TimeWindow.of(null, "2026-11-01T11:00:00Z").contains(Instant.EPOCH))
                .isTrue();
        assertThat(TimeWindow.of("2026-11-01T10:00:00Z", null).contains(Instant.MAX))
                .isTrue();
    }

    @Test
    void aMissingInstantIsOutsideABoundedWindow() {
        assertThat(TimeWindow.of("2026-11-01T10:00:00Z", null).contains(null)).isFalse();
    }

    @Test
    void refusesAnUntilNotLaterThanSince() {
        assertThatThrownBy(() -> TimeWindow.of("2026-11-01T10:00:00Z", "2026-11-01T10:00:00Z"))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("until");
                    assertThat(e.problem().detail())
                            .isEqualTo("until must be later than since, got since 2026-11-01T10:00:00Z and until"
                                    + " 2026-11-01T10:00:00Z.");
                });
    }
}
