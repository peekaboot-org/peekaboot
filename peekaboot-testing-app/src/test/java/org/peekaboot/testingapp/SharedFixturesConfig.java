package org.peekaboot.testingapp;

import io.micrometer.tracing.Tracer;
import org.peekaboot.fixtures.LateSpanController;
import org.peekaboot.fixtures.MaskingFixtureController;
import org.peekaboot.testingapp.integration.NestedConfigPropertiesFixture;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Every test-only bean the ITs assert against, in one place. A class that names its own fixture
 * ({@code @Import}, {@code @EnableConfigurationProperties}) gets a Spring context of its own,
 * and each cached context keeps a Tomcat, a Hikari pool and an H2 alive for the rest of the
 * JVM's life. Registered here, the fixtures join the context the suite already boots.
 *
 * <p>Same trick as {@link DeferredSchedulingConfig}: test sources under the application's own
 * package, a plain {@code @Configuration} the component scan finds without anyone naming it,
 * and the two profiles the IT suite boots under so the fixtures stay out of a real run and out
 * of the screenshot run. The controllers themselves live in {@code org.peekaboot.fixtures},
 * outside that scan root, so this class alone decides which contexts get them - the same
 * reasoning that keeps {@code PeekabootSecurityConfig} out of it.
 */
@Configuration
@Profile({"test", "security"})
@EnableConfigurationProperties(NestedConfigPropertiesFixture.class)
public class SharedFixturesConfig {

    @Bean
    MaskingFixtureController maskingFixtureController() {
        return new MaskingFixtureController();
    }

    @Bean
    LateSpanController lateSpanController(Tracer tracer) {
        return new LateSpanController(tracer);
    }
}
