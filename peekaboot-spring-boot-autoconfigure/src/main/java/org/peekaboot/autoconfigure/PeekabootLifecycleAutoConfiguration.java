package org.peekaboot.autoconfigure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.peekaboot.backend.lifecycle.ApplicationReadyListener;
import org.peekaboot.backend.lifecycle.ApplicationStoppedListener;
import org.peekaboot.backend.lifecycle.BuildInfoProvider;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.lifecycle.EnvironmentInfo;
import org.peekaboot.backend.lifecycle.HikariPoolInfo;
import org.peekaboot.backend.lifecycle.LifecycleEventFile;
import org.peekaboot.backend.lifecycle.LifecycleEventLog;
import org.peekaboot.backend.lifecycle.LifecycleEventRecorder;
import org.peekaboot.backend.lifecycle.LifecycleEvents;
import org.peekaboot.backend.lifecycle.LifecycleRuns;
import org.peekaboot.backend.lifecycle.ServerUrlResolver;
import org.peekaboot.backend.lifecycle.web.LifecycleController;
import org.peekaboot.backend.storage.StorageDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@AutoConfiguration(
        after = {ProjectInfoAutoConfiguration.class, PeekabootStorageAutoConfiguration.class},
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(name = "peekaboot.lifecycle.enabled", matchIfMissing = true)
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
            ObjectProvider<Map<String, DataSource>> dataSourcesProvider,
            ObjectProvider<HikariPoolInfo> hikariPoolInfoProvider) {
        List<DataSourceMetadata> dataSourceMetadataList =
                databaseMetadataListProvider.getIfAvailable(Collections::emptyList);
        Map<String, DataSource> dataSources = dataSourcesProvider.getIfAvailable(Collections::emptyMap);
        return new ApplicationReadyListener(
                environmentInfo,
                buildInfoProvider,
                serverUrlResolver,
                dataSourceMetadataList,
                dataSources,
                hikariPoolInfoProvider.getIfAvailable());
    }

    /**
     * HikariCP is optional for Peekaboot; an application on another pool has no
     * HikariDataSource class, and the ready listener must then log its banner without the
     * pool lines instead of failing the ApplicationReadyEvent. Named by string so this
     * module needs no compile dependency on HikariCP either.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.zaxxer.hikari.HikariDataSource")
    static class HikariPoolInfoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public HikariPoolInfo hikariPoolInfo() {
            return new HikariPoolInfo();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ApplicationStoppedListener applicationStoppedListener(BuildInfoProvider buildInfoProvider) {
        return new ApplicationStoppedListener(buildInfoProvider);
    }

    @Configuration(proxyBeanMethods = false)
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

    /**
     * Kept apart from the rest of this auto-configuration: the banner listeners above
     * must keep working in a context that has no storage bean at all.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(StorageDirectory.class)
    static class LifecycleEventLogConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public LifecycleEventLog lifecycleEventLog(StorageDirectory storageDirectory) {
            LifecycleEventLog log = new LifecycleEventLog(storageDirectory
                    .file(LifecycleEventFile.FILE_NAME)
                    .map(LifecycleEventFile::new)
                    .orElse(null));
            log.beginLoad();
            return log;
        }

        @Bean
        @ConditionalOnMissingBean
        public LifecycleEventRecorder lifecycleEventRecorder(
                LifecycleEventLog lifecycleEventLog,
                BuildInfoProvider buildInfoProvider,
                ObjectProvider<GitProperties> gitProperties) {
            return new LifecycleEventRecorder(lifecycleEventLog, buildInfoProvider, gitProperties.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean
        public LifecycleEvents lifecycleEvents(LifecycleEventLog lifecycleEventLog) {
            return new LifecycleEvents(lifecycleEventLog);
        }

        @Bean
        @ConditionalOnMissingBean
        public LifecycleRuns lifecycleRuns(LifecycleEventLog lifecycleEventLog) {
            return new LifecycleRuns(lifecycleEventLog);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        public LifecycleController lifecycleController(LifecycleEvents lifecycleEvents, LifecycleRuns lifecycleRuns) {
            return new LifecycleController(lifecycleEvents, lifecycleRuns);
        }
    }
}
