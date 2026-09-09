package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EnvironmentInfoTest {

    @Test
    void theActiveProfilesAreListedInOrder() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "eu");

        assertThat(new EnvironmentInfo(environment).getActiveProfilesAsString()).isEqualTo("prod, eu");
    }

    /** With nothing activated Spring runs the default profiles, so those are what the banner names. */
    @Test
    void withoutActiveProfilesTheDefaultProfilesAreNamed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");

        assertThat(new EnvironmentInfo(environment).getActiveProfilesAsString()).isEqualTo("local");
    }
}
