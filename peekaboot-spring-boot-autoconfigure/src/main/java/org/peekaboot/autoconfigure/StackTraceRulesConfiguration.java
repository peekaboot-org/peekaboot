package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The one {@link StackTraceRules} the error page and the Logs tab fold with. Imported by both
 * auto-configurations rather than being one, so either runs without the other.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PeekabootProperties.class)
class StackTraceRulesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public StackTraceRules stackTraceRules(
            PeekabootProperties properties, Environment environment, BeanFactory beanFactory) {
        PeekabootProperties.StackTrace stackTrace = properties.getStackTrace();
        return StackTraceRules.resolve(
                stackTrace.isFold(),
                stackTrace.getExclude(),
                environment.getProperty("logging.exception-conversion-word"),
                ApplicationPackages.resolve(beanFactory));
    }
}
