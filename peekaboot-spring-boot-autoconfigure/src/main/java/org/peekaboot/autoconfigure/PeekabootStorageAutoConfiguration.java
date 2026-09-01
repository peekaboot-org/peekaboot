package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Where the insights snapshot and the lifecycle log are kept. Deliberately free of
 * web and actuator conditions: the lifecycle log and its banner work in a plain
 * non-web application too.
 */
@AutoConfiguration(after = ProjectInfoAutoConfiguration.class)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@EnableConfigurationProperties(PeekabootProperties.class)
public class PeekabootStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StorageDirectory storageDirectory(
            PeekabootProperties properties, ObjectProvider<BuildProperties> buildProperties, Environment environment) {
        return StorageDirectory.resolve(
                properties.getStorage(), applicationId(buildProperties.getIfAvailable(), environment));
    }

    /**
     * The build coordinates identify the application, so two of them that share a
     * {@code spring.application.name} - or have none at all - still keep their history
     * apart. Only a build that publishes {@code build-info.properties} has them; without
     * it there is nothing but the application name to go on.
     */
    private static String applicationId(BuildProperties buildProperties, Environment environment) {
        if (buildProperties != null
                && StringUtils.hasText(buildProperties.getGroup())
                && StringUtils.hasText(buildProperties.getArtifact())) {
            return buildProperties.getGroup() + "." + buildProperties.getArtifact();
        }
        return environment.getProperty("spring.application.name");
    }
}
