package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.lifecycle.ApplicationReadyListener;
import net.osslabz.peekaboot.backend.lifecycle.BuildInfoProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the lifecycle auto-configuration is ordered after the Boot
 * auto-configurations providing the beans its conditions depend on
 * (BuildProperties, DataSource). Without explicit ordering, alphabetical
 * sorting evaluates net.osslabz.* conditions before org.springframework.*
 * registers those beans, so they could never match.
 */
class PeekabootLifecycleAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            PeekabootLifecycleAutoConfiguration.class,
            ProjectInfoAutoConfiguration.class,
            org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class));

    @Test
    void buildInfoProviderUsesBuildPropertiesWhenAvailable() {
        contextRunner
            .withPropertyValues("spring.info.build.location=classpath:test-build-info.properties")
            .run(context -> {
                assertThat(context).hasBean("buildInfoProvider");
                assertThat(context).doesNotHaveBean("buildInfoProviderFallback");
                assertThat(context.getBean(BuildInfoProvider.class).isBuildInfoAvailable()).isTrue();
                assertThat(context.getBean(BuildInfoProvider.class).getVersion()).isEqualTo("1.2.3");
            });
    }

    @Test
    void fallbackBuildInfoProviderUsedWithoutBuildProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("buildInfoProviderFallback");
            assertThat(context).hasSingleBean(BuildInfoProvider.class);
        });
    }

    @Test
    void databaseMetadataListCreatedForAutoConfiguredDataSource() {
        contextRunner
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:lifecycletest;DB_CLOSE_DELAY=-1")
            .run(context -> {
                assertThat(context).hasBean("databaseMetadataList");
                assertThat(context).hasSingleBean(ApplicationReadyListener.class);
            });
    }

    @Test
    void canBeDisabledViaProperty() {
        contextRunner
            .withPropertyValues("peekaboot.lifecycle.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ApplicationReadyListener.class));
    }
}
