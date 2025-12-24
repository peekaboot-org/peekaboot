package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.lifecycle.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.*;

@AutoConfiguration
@ConditionalOnProperty(prefix = "peekaboot.lifecycle", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PeekabootLifecycleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EnvironmentInfo environmentInfo(Environment environment) {
        return new EnvironmentInfo(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(BuildProperties.class)
    public BuildInfoProvider buildInfoProvider(BuildProperties buildProperties) {
        return new BuildInfoProvider(buildProperties);
    }

    @Bean
    @ConditionalOnMissingBean(BuildInfoProvider.class)
    public BuildInfoProvider buildInfoProviderFallback() {
        return new BuildInfoProvider(null);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApplicationReadyListener applicationReadyListener(
            EnvironmentInfo environmentInfo,
            BuildInfoProvider buildInfoProvider,
            ObjectProvider<List<DatabaseMetadata>> databaseMetadataListProvider,
            ObjectProvider<Map<String, DataSource>> dataSourcesProvider) {
        List<DatabaseMetadata> databaseMetadataList = databaseMetadataListProvider.getIfAvailable(Collections::emptyList);
        Map<String, DataSource> dataSources = dataSourcesProvider.getIfAvailable(Collections::emptyMap);
        return new ApplicationReadyListener(environmentInfo, buildInfoProvider, databaseMetadataList, dataSources);
    }


    @Configuration
    @ConditionalOnBean(DataSource.class)
    static class DatabaseMetadataConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "databaseMetadataList")
        public List<DatabaseMetadata> databaseMetadataList(Map<String, DataSource> dataSources) {
            List<DatabaseMetadata> metadataList = new ArrayList<>();
            dataSources.forEach((name, dataSource) -> {
                DatabaseMetadata metadata = DatabaseMetadata.fromDataSource(name, dataSource);
                metadataList.add(metadata);
            });
            return metadataList;
        }
    }

}