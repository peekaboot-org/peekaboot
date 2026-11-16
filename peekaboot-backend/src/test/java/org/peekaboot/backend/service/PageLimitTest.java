package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.api.TraceView;
import org.peekaboot.backend.problem.ApiProblemException;

class PageLimitTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void anAbsentLimitServesAHundred(String limit) {
        assertThat(PageLimit.of(limit)).isEqualTo(100);
    }

    @Test
    void readsEveryLimitFromOneToTenThousand() {
        assertThat(PageLimit.of("1")).isEqualTo(1);
        assertThat(PageLimit.of("10000")).isEqualTo(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "10001", "-1", "ten"})
    void refusesALimitOutsideItsRange(String limit) {
        assertThatThrownBy(() -> PageLimit.of(limit)).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.problem().parameter()).isEqualTo("limit");
            assertThat(e.problem().detail())
                    .isEqualTo("limit must be an integer from 1 to 10000, got '" + limit + "'.");
        });
    }

    @Test
    void aFullViewPageHoldsAtMostAHundredRows() {
        assertThat(PageLimit.of("100", TraceView.FULL)).isEqualTo(100);
        assertThat(PageLimit.of(null, TraceView.FULL)).isEqualTo(100);
        assertThat(PageLimit.of("10000", TraceView.SUMMARY)).isEqualTo(10_000);
        assertThatThrownBy(() -> PageLimit.of("101", TraceView.FULL))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("limit");
                    assertThat(e.problem().detail())
                            .isEqualTo("limit must be at most 100 with view=full, got '101'. Read on with the next"
                                    + " value, or list with view=summary and fetch a trace by its id.");
                });
    }

    @Test
    void aFullViewLimitOutsideTheRangeKeepsThatRefusal() {
        assertThatThrownBy(() -> PageLimit.of("10001", TraceView.FULL))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().detail())
                                .isEqualTo("limit must be an integer from 1 to 10000, got '10001'."));
    }
}
