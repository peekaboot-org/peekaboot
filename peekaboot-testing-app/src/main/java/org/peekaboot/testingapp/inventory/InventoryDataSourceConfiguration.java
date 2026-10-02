package org.peekaboot.testingapp.inventory;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The second DataSource, after Boot's "Configure Two DataSources" how-to: not a default
 * candidate, so the primary keeps its auto-configuration and every unqualified injection
 * point, and Boot's own Liquibase migrates this one through {@link LiquibaseDataSource}.
 */
@Configuration(proxyBeanMethods = false)
class InventoryDataSourceConfiguration {

    @Bean(defaultCandidate = false)
    @Qualifier("inventory")
    @ConfigurationProperties("app.datasource.inventory")
    DataSourceProperties inventoryDataSourceProperties() {

        return new DataSourceProperties();
    }

    @Bean(defaultCandidate = false)
    @Qualifier("inventory")
    @LiquibaseDataSource
    @ConfigurationProperties("app.datasource.inventory.configuration")
    HikariDataSource inventoryDataSource(@Qualifier("inventory") DataSourceProperties properties) {

        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(defaultCandidate = false)
    @Qualifier("inventory")
    JdbcClient inventoryJdbcClient(@Qualifier("inventory") DataSource dataSource) {

        return JdbcClient.create(dataSource);
    }
}
