package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.config.UiTracingProperties;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;


@AutoConfiguration(after = PeekabootTracingAutoConfiguration.class)
@ConditionalOnClass({HealthEndpoint.class, InfoEndpoint.class})
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({PeekabootProperties.class, UiTracingProperties.class})
@ComponentScan(basePackages = {
    "net.osslabz.peekaboot.backend.controller",
    "net.osslabz.peekaboot.backend.service",
    "net.osslabz.peekaboot.backend.mapper",
    "net.osslabz.peekaboot.backend.config",
    "net.osslabz.peekaboot.backend.actuator"
})
public class PeekabootAutoConfiguration {
}