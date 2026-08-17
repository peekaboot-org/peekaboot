package org.peekaboot.autoconfigure;

import org.peekaboot.backend.lifecycle.*;
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

@AutoConfiguration(
        after = org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
)
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true")
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
    public ServerUrlResolver serverUrlResolver(Environment environment) {
        return new ServerUrlResolver(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApplicationReadyListener applicationReadyListener(
            EnvironmentInfo environmentInfo,
            BuildInfoProvider buildInfoProvider,
            ServerUrlResolver serverUrlResolver,
            ObjectProvider<List<DataSourceMetadata>> databaseMetadataListProvider,
            ObjectProvider<Map<String, DataSource>> dataSourcesProvider) {
        List<DataSourceMetadata> dataSourceMetadataList = databaseMetadataListProvider.getIfAvailable(Collections::emptyList);
        Map<String, DataSource> dataSources = dataSourcesProvider.getIfAvailable(Collections::emptyMap);
        return new ApplicationReadyListener(environmentInfo, buildInfoProvider, serverUrlResolver, dataSourceMetadataList, dataSources);
    }


    @Configuration
    @ConditionalOnBean(DataSource.class)
    static class DatabaseMetadataConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "databaseMetadataList")
        public List<DataSourceMetadata> databaseMetadataList(Map<String, DataSource> dataSources) {
            List<DataSourceMetadata> metadataList = new ArrayList<>();
            dataSources.forEach((name, dataSource) ->
                DataSourceMetadata.fromDataSource(name, dataSource).ifPresent(metadataList::add));
            return metadataList;
        }
    }

}