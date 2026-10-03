package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.listener.RecordingListener;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.listener.EventListenerObservationPostProcessor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class EventListenerInstrumentationAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventListenerInstrumentationAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void registersThePostProcessorWhenObservationRegistryBeanPresent() {
        contextRunner.withUserConfiguration(ObservationRegistryConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EventListenerObservationPostProcessor.class);
        });
    }

    @Test
    void doesNotRegisterThePostProcessorWithoutAnObservationRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EventListenerObservationPostProcessor.class);
        });
    }

    @Test
    void doesNotRegisterThePostProcessorWhenEventListenerInstrumentationDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.tracing.event-listeners=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EventListenerObservationPostProcessor.class);
                });
    }

    /** A listener span is tracing; with tracing off there is no store for it to land in. */
    @Test
    void doesNotRegisterThePostProcessorWhenTracingDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EventListenerObservationPostProcessor.class);
                });
    }

    @Test
    void doesNotRegisterThePostProcessorWhenPeekabootDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EventListenerObservationPostProcessor.class);
                });
    }

    @Test
    void userSuppliedSameNamedPostProcessorReplacesTheDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, UserPostProcessorConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("peekabootEventListenerObservationPostProcessor"))
                            .isSameAs(UserPostProcessorConfig.POST_PROCESSOR);
                    assertThat(context).doesNotHaveBean(EventListenerObservationPostProcessor.class);
                });
    }

    /** Boot configures the registry in a later post-processor, so creating it early leaves handlers unseen. */
    @Test
    void leavesTheObservationRegistryToBootsOwnConfiguration() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class))
                .withUserConfiguration(RecordingHandlerConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EventListenerObservationPostProcessor.class);

                    Observation.start("probe", context.getBean(ObservationRegistry.class))
                            .stop();

                    assertThat(context.getBean(RecordingHandler.class).started).contains("probe");
                });
    }

    /** Peekaboot cannot tell which of two registries is the application's, and must not fail its listener over it. */
    @Test
    void aListenerRunsWithoutASpanWhenTwoObservationRegistriesExist() {
        contextRunner
                .withUserConfiguration(TwoObservationRegistriesConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RecordingListener listener = context.getBean(RecordingListener.class);
                    assertThat(AopUtils.isCglibProxy(listener)).isTrue();

                    Observation.createNotStarted(
                                    "parent", context.getBean("observationRegistry", ObservationRegistry.class))
                            .observe(() -> context.publishEvent(new RecordingListener.Ping()));

                    assertThat(listener.heard()).hasSize(1);
                    assertThat(context.getBean(RecordingHandler.class).started).containsExactly("parent");
                });
    }

    @Configuration
    static class TwoObservationRegistriesConfig {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        ObservationRegistry observationRegistry(RecordingHandler handler) {
            return registryReportingTo(handler);
        }

        @Bean
        ObservationRegistry otherObservationRegistry(RecordingHandler handler) {
            return registryReportingTo(handler);
        }

        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }

        private static ObservationRegistry registryReportingTo(RecordingHandler handler) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(handler);
            return registry;
        }
    }

    @Configuration
    static class ObservationRegistryConfig {
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    @Configuration
    static class UserPostProcessorConfig {

        static final BeanPostProcessor POST_PROCESSOR = new BeanPostProcessor() {};

        @Bean
        static BeanPostProcessor peekabootEventListenerObservationPostProcessor() {
            return POST_PROCESSOR;
        }
    }

    @Configuration
    static class RecordingHandlerConfig {
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    static class RecordingHandler implements ObservationHandler<Observation.Context> {

        final List<String> started = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStart(Observation.Context context) {
            started.add(context.getName());
        }
    }
}
