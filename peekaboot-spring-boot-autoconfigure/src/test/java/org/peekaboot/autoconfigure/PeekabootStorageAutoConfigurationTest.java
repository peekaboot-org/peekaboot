package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PeekabootStorageAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PeekabootStorageAutoConfiguration.class));

    private static BuildProperties buildProperties(String group, String artifact) {
        Properties entries = new Properties();
        if (group != null) {
            entries.setProperty("group", group);
        }
        if (artifact != null) {
            entries.setProperty("artifact", artifact);
        }
        return new BuildProperties(entries);
    }

    private static Path home(String folder) {
        return Path.of(System.getProperty("user.home"), ".peekaboot", folder);
    }

    @Test
    void noStorageDirectoryWithoutPeekaboot() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(StorageDirectory.class));
    }

    /**
     * Detection of a local launch is what switches storage on (see
     * PeekabootDefaultsEnvironmentPostProcessor); no post-processor runs here, so the
     * property default applies and nothing is written.
     */
    @Test
    void storageIsOffWithoutTheLaunchContextDefault() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> assertThat(
                                context.getBean(StorageDirectory.class).isEnabled())
                        .isFalse());
    }

    @Test
    void theBuildCoordinatesNameTheDirectory() {
        contextRunner
                .withBean(BuildProperties.class, () -> buildProperties("com.example", "orders-service"))
                .withPropertyValues("peekaboot.enabled=true", "spring.application.name=orders")
                .run(context -> assertThat(
                                context.getBean(StorageDirectory.class).root())
                        .isEqualTo(home("com.example.orders-service")));
    }

    @Test
    void theApplicationNameNamesTheDirectoryWithoutBuildInformation() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=true", "spring.application.name=orders")
                .run(context -> assertThat(
                                context.getBean(StorageDirectory.class).root())
                        .isEqualTo(home("orders")));
    }

    @Test
    void incompleteBuildInformationFallsBackToTheApplicationName() {
        contextRunner
                .withBean(BuildProperties.class, () -> buildProperties("com.example", null))
                .withPropertyValues("peekaboot.enabled=true", "spring.application.name=orders")
                .run(context -> assertThat(
                                context.getBean(StorageDirectory.class).root())
                        .isEqualTo(home("orders")));
    }

    @Test
    void anExplicitDirectoryWins() {
        contextRunner
                .withBean(BuildProperties.class, () -> buildProperties("com.example", "orders-service"))
                .withPropertyValues(
                        "peekaboot.enabled=true", "peekaboot.storage.enabled=true", "peekaboot.storage.dir=/var/tmp/pk")
                .run(context -> {
                    StorageDirectory directory = context.getBean(StorageDirectory.class);
                    assertThat(directory.isEnabled()).isTrue();
                    assertThat(directory.root()).isEqualTo(Path.of("/var/tmp/pk"));
                });
    }
}
