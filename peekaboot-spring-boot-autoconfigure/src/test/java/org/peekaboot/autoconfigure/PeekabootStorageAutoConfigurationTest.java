package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PeekabootStorageAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PeekabootStorageAutoConfiguration.class));

    @Test
    void noStorageDirectoryWithoutPeekaboot() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(StorageDirectory.class));
    }

    @Test
    void theDirectoryIsNamedAfterTheApplicationAndDisabledByDefault() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true", "spring.application.name=orders")
                .run(context -> {
                    StorageDirectory directory = context.getBean(StorageDirectory.class);
                    assertThat(directory.isEnabled()).isFalse();
                    assertThat(directory.root())
                            .isEqualTo(Path.of(System.getProperty("user.home"), ".peekaboot", "orders"));
                });
    }

    @Test
    void anExplicitDirectoryWins() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.enabled=true", "peekaboot.storage.enabled=true", "peekaboot.storage.dir=/var/tmp/pk")
                .run(context -> {
                    StorageDirectory directory = context.getBean(StorageDirectory.class);
                    assertThat(directory.isEnabled()).isTrue();
                    assertThat(directory.root()).isEqualTo(Path.of("/var/tmp/pk"));
                });
    }
}
