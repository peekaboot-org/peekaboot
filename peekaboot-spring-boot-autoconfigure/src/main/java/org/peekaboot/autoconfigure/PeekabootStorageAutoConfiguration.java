package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Where the insights snapshot and the lifecycle log are kept. Deliberately free of
 * web and actuator conditions: the lifecycle log and its banner work in a plain
 * non-web application too.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PeekabootProperties.class)
public class PeekabootStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StorageDirectory storageDirectory(PeekabootProperties properties, Environment environment) {
        return StorageDirectory.resolve(properties.getStorage(), environment.getProperty("spring.application.name"));
    }
}
