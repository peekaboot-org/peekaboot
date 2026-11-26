package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;

/** The contract under Micrometer's observation conventions, Spring Boot's default, named explicitly. */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.observations.conventions=micrometer")
class MicrometerConventionsIT extends ObservationConventionContract {

    @Override
    protected void assertConventionIsActive() {
        awaitTimedRequest(MICROMETER_SERVER_METER);

        assertThat(meterRegistry.find(OPEN_TELEMETRY_SERVER_METER).meters()).isEmpty();
    }

    @Override
    protected void assertJvmMetersFollowTheConvention() {
        assertThat(meterRegistry.find("process.cpu.usage").gauges()).isNotEmpty();
        assertThat(meterRegistry.find("jvm.cpu.recent_utilization").meters()).isEmpty();
        assertThat(meterRegistry.find("jvm.memory.used").tagKeys("area").meters())
                .isNotEmpty();
    }
}
