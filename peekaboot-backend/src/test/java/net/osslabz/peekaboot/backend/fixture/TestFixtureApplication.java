package net.osslabz.peekaboot.backend.fixture;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
    "net.osslabz.peekaboot.backend.fixture",
    "net.osslabz.peekaboot.backend.controller",
    "net.osslabz.peekaboot.backend.service",
    "net.osslabz.peekaboot.backend.filter",
    "net.osslabz.peekaboot.backend.devtoolbar",
    "net.osslabz.peekaboot.autoconfigure"
})
public class TestFixtureApplication {
}
