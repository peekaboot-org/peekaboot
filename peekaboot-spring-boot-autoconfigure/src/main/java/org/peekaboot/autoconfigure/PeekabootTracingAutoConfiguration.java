package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootProperties;
import org.peekaboot.backend.mapper.trace.SqlFormatter;
import org.peekaboot.backend.mapper.trace.TraceResources;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.storage.StorageDirectory;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.digest.ErrorDigestFile;
import org.peekaboot.backend.tracing.digest.ErrorDigestRecorder;
import org.peekaboot.backend.tracing.journal.TraceJournal;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
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
import org.springframework.context.annotation.Import;

/**
 * Servlet-only because everything that reads the store - the trace service, controller and filters - is.
 * After storage, whose directory the trace journal writes into. Imports the masking engine and the stack
 * trace rules, which the error digest maps a failed trace's summary with.
 */
@AutoConfiguration(after = PeekabootStorageAutoConfiguration.class)
@ConditionalOnPeekabootTracing
@EnableConfigurationProperties(PeekabootTracingProperties.class)
@Import({MaskingConfiguration.class, StackTraceRulesConfiguration.class})
public class PeekabootTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceStore peekabootTraceStore(PeekabootTracingProperties properties) {
        return new InMemoryTraceStore(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorDigest peekabootErrorDigest() {
        return new ErrorDigest();
    }

    /** No SQL formatting: the digest reads a summary's cause and route, which none touches. */
    @Bean
    @ConditionalOnMissingBean
    public ErrorDigestRecorder peekabootErrorDigestRecorder(
            TraceStore traceStore,
            ErrorDigest errorDigest,
            MaskingEngine maskingEngine,
            PeekabootTracingProperties properties,
            StackTraceRules stackTraceRules) {
        return new ErrorDigestRecorder(
                traceStore,
                errorDigest,
                new TraceResources(maskingEngine, SqlFormatter.NONE, properties, stackTraceRules));
    }

    /** Hands every input to the trace journal where one runs, else to the store and then the error digest. */
    @Bean
    @ConditionalOnMissingBean
    public TraceStoreEventListener peekabootTraceStoreEventListener(
            ErrorDigestRecorder errorDigestRecorder,
            ObjectProvider<TraceJournal> traceJournal,
            ApplicationContext applicationContext) {
        TraceJournal journal = traceJournal.getIfAvailable();
        return new TraceStoreEventListener(
                journal != null ? journal : errorDigestRecorder.liveInputs(), applicationContext);
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
                ErrorDigestRecorder errorDigestRecorder,
                ErrorDigest errorDigest,
                StorageDirectory storageDirectory,
                PeekabootProperties properties,
                PeekabootTracingProperties tracingProperties) {
            return TraceJournal.start(
                    traceStore,
                    errorDigestRecorder.liveInputs(),
                    new ErrorDigestFile(storageDirectory.root(), errorDigest),
                    storageDirectory.root(),
                    properties.getStorage().getTraces(),
                    tracingProperties);
        }
    }
}
