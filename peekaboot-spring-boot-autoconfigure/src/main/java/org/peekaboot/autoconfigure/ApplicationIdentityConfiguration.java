package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.ApplicationIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The one {@link ApplicationIdentity} the storage folder, the guard's username and the banners
 * are named from. Imported by each of those auto-configurations, like {@link MaskingConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationIdentityConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationIdentity applicationIdentity(
            ObjectProvider<BuildProperties> buildProperties, Environment environment) {
        return new ApplicationIdentity(
                buildProperties.getIfAvailable(), environment.getProperty("spring.application.name"));
    }
}
