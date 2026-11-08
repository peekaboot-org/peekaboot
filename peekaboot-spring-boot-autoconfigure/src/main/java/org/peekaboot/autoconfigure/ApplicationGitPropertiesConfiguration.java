package org.peekaboot.autoconfigure;

import org.peekaboot.backend.lifecycle.ApplicationGitProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The one {@link ApplicationGitProperties} the Overview and the lifecycle log read. Imported by
 * each of those auto-configurations, like {@link MaskingConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationGitPropertiesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApplicationGitProperties peekabootApplicationGitProperties(
            ObjectProvider<GitProperties> gitProperties,
            Environment environment,
            ApplicationContext applicationContext) {
        // Boot's bean reads the first git.properties on the classpath, which can be a library's.
        GitProperties hostLocated =
                environment.containsProperty("spring.info.git.location") ? gitProperties.getIfAvailable() : null;
        return new ApplicationGitProperties(hostLocated, applicationContext);
    }
}
