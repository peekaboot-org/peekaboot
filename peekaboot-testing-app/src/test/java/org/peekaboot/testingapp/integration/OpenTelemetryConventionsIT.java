package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.peekaboot.fixtures.RequestEchoController;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * The contract under OpenTelemetry's semantic conventions, with the MDC keys renamed as Boot 4.2 allows.
 * Boot 4.1 ignores all three properties and runs Micrometer's conventions, so there the pre-assertions
 * are skipped and the contract proves only that nothing broke.
 *
 * <p>The tag runs the class in a JVM of its own; the testing-app pom's {@code peekaboot.it.otel-conventions} says why.
 */
@Tag("otel-conventions")
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.observations.conventions=open-telemetry",
            "management.tracing.mdc.trace-id-key=" + OpenTelemetryConventionsIT.TRACE_ID_KEY,
            "management.tracing.mdc.span-id-key=" + OpenTelemetryConventionsIT.SPAN_ID_KEY
        })
class OpenTelemetryConventionsIT extends ObservationConventionContract {

    static final String TRACE_ID_KEY = "tid";

    static final String SPAN_ID_KEY = "sid";

    @Override
    protected void assertConventionIsActive() {
        if (!BootLine.isAtLeast(4, 2)) {
            return;
        }
        awaitTimedRequest(OPEN_TELEMETRY_SERVER_METER);
        assertThat(meterRegistry.find(MICROMETER_SERVER_METER).meters()).isEmpty();

        JsonNode rootTags = personsTrace().path("data").path("rootSpan").path("tags");
        assertThat(rootTags.has("http.request.method"))
                .as("root span tags %s", rootTags)
                .isTrue();
        assertThat(rootTags.has("method")).as("root span tags %s", rootTags).isFalse();

        List<String> mdcKeys = api.getJson(RequestEchoController.MDC_KEYS_PATH)
                .valueStream()
                .map(JsonNode::asString)
                .toList();
        assertThat(mdcKeys).contains(TRACE_ID_KEY, SPAN_ID_KEY).doesNotContain("traceId", "spanId");
    }

    @Override
    protected void assertJvmMetersFollowTheConvention() {
        if (!BootLine.isAtLeast(4, 2)) {
            return;
        }
        assertThat(meterRegistry.find("jvm.cpu.recent_utilization").gauges()).isNotEmpty();
        assertThat(meterRegistry.find("process.cpu.usage").meters()).isEmpty();
        assertThat(meterRegistry.find("jvm.memory.used").meters()).isNotEmpty();
        assertThat(meterRegistry.find("jvm.memory.used").tagKeys("area").meters())
                .isEmpty();
    }
}
