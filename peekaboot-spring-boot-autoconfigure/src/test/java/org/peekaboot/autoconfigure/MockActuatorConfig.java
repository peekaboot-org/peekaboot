package org.peekaboot.autoconfigure;

import static org.mockito.Mockito.mock;

import org.peekaboot.backend.service.PeekabootActuatorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stands in for the actuator service the auto-configurations under test depend on. */
@Configuration
class MockActuatorConfig {

    @Bean
    PeekabootActuatorService peekabootActuatorService() {
        return mock(PeekabootActuatorService.class);
    }
}
