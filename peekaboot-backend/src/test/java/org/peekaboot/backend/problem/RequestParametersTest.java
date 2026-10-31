package org.peekaboot.backend.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
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
}
