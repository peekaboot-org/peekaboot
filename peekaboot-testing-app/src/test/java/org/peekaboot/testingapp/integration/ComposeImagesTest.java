package org.peekaboot.testingapp.integration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * The demo runs on the engines its ITs test against. Dependabot watches neither file, so a bump
 * made by hand in one fails here until the other follows.
 */
class ComposeImagesTest {

    @Test
    void composeRunsTheImagesTheItsStart() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("compose.yml"));
        Properties compose = requireNonNull(yaml.getObject(), "compose.yml read as nothing");

        assertThat(compose.getProperty("services.mysql.image")).isEqualTo(TestImages.MYSQL);
        assertThat(compose.getProperty("services.redis.image")).isEqualTo(TestImages.REDIS);
        assertThat(compose.getProperty("services.httpbin.image")).isEqualTo(TestImages.HTTPBIN);
    }
}
