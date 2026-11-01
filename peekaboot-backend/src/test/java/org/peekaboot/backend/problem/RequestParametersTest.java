package org.peekaboot.backend.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
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

    @Test
    void readsAnInstantWithAnOffset() {
        assertThat(RequestParameters.instant("since", "2026-11-01T10:00:00+01:00"))
                .isEqualTo(Instant.parse("2026-11-01T09:00:00Z"));
        assertThat(RequestParameters.instant("since", " 2026-11-01T09:00:00.123456Z "))
                .isEqualTo(Instant.parse("2026-11-01T09:00:00.123456Z"));
    }

    /** A + typed into a query string arrives as a space. */
    @Test
    void anUnencodedPlusIsRefusedWithHowToSendIt() {
        assertThatThrownBy(() -> RequestParameters.instant("since", "2026-11-01T10:00:00 01:00"))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("since");
                    assertThat(e.problem().detail())
                            .isEqualTo("since must be an ISO-8601 instant such as 2026-11-01T10:15:30Z, got"
                                    + " '2026-11-01T10:00:00 01:00'. Send a + in an offset as %2B.");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"yesterday", "2026-11-01", "1730455200000", "2026-13-01T00:00:00Z", "2026-11-01T10:00Z"})
    void refusesWhatIsNoInstant(String value) {
        assertThatThrownBy(() -> RequestParameters.instant("until", value))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo("until"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void readsAnAbsentInstantAsNothing(String value) {
        assertThat(RequestParameters.instant("since", value)).isNull();
    }
}
