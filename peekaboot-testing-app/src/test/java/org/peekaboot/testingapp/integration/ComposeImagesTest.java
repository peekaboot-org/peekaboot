package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A major tag moves under the build without a commit. Dependabot bumps {@code compose.yml}, and
 * the ITs start what it names ({@link TestImages}).
 */
class ComposeImagesTest {

    @Test
    void composePinsEveryImagePastItsMajorVersion() {
        assertThat(TestImages.composeImages())
                .containsKeys("postgres", "mysql", "redis", "httpbin")
                .allSatisfy((service, image) -> assertThat(image).as(service).matches(".+:\\d+\\.\\d+(\\.\\d+)?"));
    }
}
