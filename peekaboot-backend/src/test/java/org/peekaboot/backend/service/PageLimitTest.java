package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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
}
