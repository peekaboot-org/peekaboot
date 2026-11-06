package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.storage.StorageDirectory;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.journal.TraceJournal;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceInputs;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet-only because everything that reads the store - the trace service, controller and filters - is.
 * After storage, whose directory the trace journal writes into.
 */
@AutoConfiguration(after = PeekabootStorageAutoConfiguration.class)
@ConditionalOnPeekabootTracing
@EnableConfigurationProperties(PeekabootTracingProperties.class)
public class PeekabootTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceStore traceStore(PeekabootTracingProperties properties) {
        return new InMemoryTraceStore(properties);
    }

    /** Hands every input to the trace journal where one runs, else straight into the store. */
    @Bean
    @ConditionalOnMissingBean
    public TraceStoreEventListener traceStoreEventListener(
            TraceStore traceStore, ObjectProvider<TraceJournal> traceJournal, ApplicationContext applicationContext) {
        TraceJournal journal = traceJournal.getIfAvailable();
        return new TraceStoreEventListener(
                journal != null ? journal : TraceInputs.into(traceStore), applicationContext);
    }

    /** Storage on, and traces not switched off on their own; nested, since each element keeps one boolean-property condition. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.STORAGE_ENABLED)
    @ConditionalOnBean(StorageDirectory.class)
    static class TraceJournalConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.STORAGE_TRACES_ENABLED, matchIfMissing = true)
        public TraceJournal peekabootTraceJournal(
                TraceStore traceStore,
                StorageDirectory storageDirectory,
                PeekabootProperties properties,
                PeekabootTracingProperties tracingProperties) {
            return TraceJournal.start(
                    traceStore, storageDirectory.root(), properties.getStorage().getTraces(), tracingProperties);
        }
    }
}
