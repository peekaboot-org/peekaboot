package org.peekaboot.autoconfigure;

import javax.sql.DataSource;
import org.peekaboot.backend.datasource.DataSourceConnections;
import org.peekaboot.backend.datasource.HikariPoolInfo;
import org.peekaboot.backend.health.HealthConnectionSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Names each DataSource's target in the health report. Has its own {@link HikariPoolInfo}: the bean
 * the lifecycle auto-configuration registers is gated by {@code peekaboot.lifecycle.enabled}, which
 * has nothing to do with this report.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
class DataSourceHealthConnectionsConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "peekabootDataSourceHealthConnections")
    public HealthConnectionSource peekabootDataSourceHealthConnections(ConfigurableListableBeanFactory beanFactory) {
        return new DataSourceConnections(
                ApplicationBeans.ofType(beanFactory, DataSource.class), new HikariPoolInfo()::jdbcUrlOf);
    }
}
