package org.peekaboot.autoconfigure;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** The sources that say what each kind of health component connects to. */
@Configuration(proxyBeanMethods = false)
@Import(DataSourceHealthConnectionsConfiguration.class)
class HealthConnectionSourcesConfiguration {}
