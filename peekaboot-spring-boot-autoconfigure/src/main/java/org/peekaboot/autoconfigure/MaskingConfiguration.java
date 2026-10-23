package org.peekaboot.autoconfigure;

import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link MaskingEngine} every masking site injects: the dashboard's mappers and
 * services, the toolbar's request capture and the lifecycle banner. Imported by each of those
 * auto-configurations rather than being one, so it exists wherever any of them runs, the
 * banner's plain non-web application and the toolbar without the actuator included.
 */
@Configuration(proxyBeanMethods = false)
class MaskingConfiguration {

    /** Stateless, so one instance serves every site; a bean so each site injects it rather than building its own. */
    @Bean
    @ConditionalOnMissingBean
    public MaskingEngine maskingEngine() {
        return new MaskingEngine();
    }
}
