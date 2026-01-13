package net.osslabz.peekaboot.backend.fixture;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.config.UiTracingProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(scanBasePackages = {
    "net.osslabz.peekaboot.backend",
    "net.osslabz.peekaboot.autoconfigure"
})
@EnableConfigurationProperties({PeekabootProperties.class, UiTracingProperties.class})
public class TestFixtureApplication {
}
