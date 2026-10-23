package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.ApplicationIdentity;
import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Where the insights snapshot and the lifecycle log are kept. Deliberately free of
 * web and actuator conditions: the lifecycle log and its banner work in a plain
 * non-web application too.
 */
@AutoConfiguration(after = ProjectInfoAutoConfiguration.class)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@EnableConfigurationProperties(PeekabootProperties.class)
@Import(ApplicationIdentityConfiguration.class)
public class PeekabootStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StorageDirectory storageDirectory(PeekabootProperties properties, ApplicationIdentity applicationIdentity) {
        return StorageDirectory.resolve(properties.getStorage(), applicationIdentity.qualifiedId());
    }
}
