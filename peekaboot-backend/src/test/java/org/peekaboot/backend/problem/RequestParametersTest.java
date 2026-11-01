package org.peekaboot.backend.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.tracing.store.TraceBucket;

class RequestParametersTest {

    @Test
    void readsAConstantInAnyCase() {
        assertThat(RequestParameters.enumValue("bucket", TraceBucket.class, "errors"))
                .isEqualTo(TraceBucket.ERRORS);
    }

    @Test
    void refusesAnUnknownValueNamingTheParameterAndWhatItAccepts() {
        assertThatThrownBy(() -> RequestParameters.enumValue("bucket", TraceBucket.class, "eror"))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().status()).isEqualTo(400);
                    assertThat(e.problem().parameter()).isEqualTo("bucket");
                    assertThat(e.problem().detail()).isEqualTo("Unknown bucket 'eror'. Accepted: ALL, ERRORS, SLOW.");
                });
    }

    @Test
    void listsTheValuesAcceptedBesideTheConstants() {
        assertThatThrownBy(() -> RequestParameters.enumValue("rootActionType", RootActionType.class, "NOPE", "*"))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().detail()).endsWith(", UNKNOWN, *."));
    }

    @Test
    void refusesABlankValue() {
        assertThatThrownBy(() -> RequestParameters.enumValue("rootActionType", RootActionType.class, " "))
                .isInstanceOf(ApiProblemException.class);
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "' 42 ', 42", "+7, 7", "10, 10"})
    void readsAnIntegerInItsRange(String value, int expected) {
        assertThat(RequestParameters.integer("n", value, 1, 50)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "1e3", "0x10", "99999999999", "ten", "4 2"})
    void refusesAnIntegerOutsideItsRangeOrNoIntegerAtAll(String value) {
        assertThatThrownBy(() -> RequestParameters.integer("n", value, 1, 50))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("n");
                    assertThat(e.problem().detail())
                            .isEqualTo("n must be an integer from 1 to 50, got '" + value + "'.");
                });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void readsAnAbsentOrBlankValueAsNothing(String value) {
        assertThat(RequestParameters.integer("n", value, 1, 50)).isNull();
        assertThat(RequestParameters.text(value)).isNull();
    }

    @Test
    void keepsTextAsWritten() {
        assertThat(RequestParameters.text(" /persons/{id} ")).isEqualTo(" /persons/{id} ");
    }
}
