package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.testingapp.integration.ActuatorInsightsJson.findEnvironmentPropertyValue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Proves the other required row of spec §2 end to end through the real HTTP API: outside
 * local development, actuator value visibility is absent - not an explicit {@code never} (see
 * {@code PeekabootDefaultsEnvironmentPostProcessorTest.doesNotShowActuatorValuesOutsideLocalDevelopment}
 * and {@code .setsNoActuatorValueVisibilityWhenPeekabootIsDisabled}) - which resolves to
 * Spring's own default, which is {@code never}. So off-local every property masks, not just
 * sensitive-looking ones: {@code server.port} is deliberately not a secret, unlike the
 * {@code spring.datasource.password} fixture {@code ActuatorMaskingIT} and
 * {@code UnmaskingDisabledIT} check - a secret masks either way (selectively, via
 * {@code MaskingEngine}, or blanket, via Spring's own {@code Sanitizer}), so it would not
 * distinguish this row from theirs.
 *
 * <p>This test sets {@code show-values=never} explicitly rather than actually running outside
 * a local-dev launch context, which a JUnit test cannot reproduce ({@code LocalDevDetector}
 * requires the {@code main} thread on the JDK's {@code AppClassLoader} with no test-framework
 * frames on the stack - conditions no JUnit run ever satisfies). That is exactly the value the
 * detection source leaves absent outside local development, and absence resolves to Spring's
 * own default, which is this same {@code never} - so the observable posture through the HTTP
 * API is identical either way.
 *
 * <p>A separate Spring context from the rest of this package's masking tests is required:
 * {@code application-test.yml} opts the shared {@code test} profile into
 * {@code show-values=always} so those tests exercise {@code MaskingEngine}'s selective masking
 * (the local-dev-like posture), which this test deliberately overrides back to {@code never}.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.endpoint.env.show-values=never", "management.endpoint.configprops.show-values=never"})
@ActiveProfiles("test")
class ActuatorValuesMaskedOutsideLocalDevelopmentIT {

    @LocalServerPort
    private int port;

    private PeekabootApi api;

    @BeforeEach
    void connect() {
        api = new PeekabootApi(port);
    }

    @Test
    void insightsEndpointMasksANonSensitivePropertyWhenValuesAreNotShown() {
        JsonNode environment =
                api.getJson("/peekaboot/api/actuator/all/insights").path("environment");

        JsonNode portValue = findEnvironmentPropertyValue(environment, "server.port");
        assertThat(portValue)
                .as("server.port must be present in some environment property source")
                .isNotNull();
        assertThat(portValue.asString()).isEqualTo("******");
    }
}
