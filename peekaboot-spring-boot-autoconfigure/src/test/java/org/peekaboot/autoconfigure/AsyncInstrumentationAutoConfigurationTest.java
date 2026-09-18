package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.async.AsyncTaskDecorator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

class AsyncInstrumentationAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AsyncInstrumentationAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void registersTheDecoratorWhenObservationRegistryBeanPresent() {
        contextRunner.withUserConfiguration(ObservationRegistryConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AsyncTaskDecorator.class);
        });
    }

    @Test
    void doesNotRegisterTheDecoratorWithoutAnObservationRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AsyncTaskDecorator.class);
        });
    }

    @Test
    void doesNotRegisterTheDecoratorWhenAsyncInstrumentationDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.tracing.async=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AsyncTaskDecorator.class);
                });
    }

    /** Handing work to an executor is tracing; with tracing off there is no store to land in. */
    @Test
    void doesNotRegisterTheDecoratorWhenTracingDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AsyncTaskDecorator.class);
                });
    }

    @Test
    void doesNotRegisterTheDecoratorWhenPeekabootDisabled() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class)
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AsyncTaskDecorator.class);
                });
    }

    /**
     * Matched by name, not by type. ContextPropagatingTaskDecorator is itself a TaskDecorator
     * bean, so a by-type condition would back Peekaboot's decorator off in exactly the
     * configuration this feature exists to serve.
     */
    @Test
    void anApplicationsOwnTaskDecoratorDoesNotBackPeekabootsOff() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, PropagatingDecoratorConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AsyncTaskDecorator.class);
                    assertThat(context.getBeansOfType(TaskDecorator.class)).hasSize(2);
                });
    }

    @Test
    void userSuppliedSameNamedDecoratorReplacesTheDefault() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, UserDecoratorConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("peekabootAsyncTaskDecorator")).isSameAs(UserDecoratorConfig.DECORATOR);
                });
    }

    /**
     * Lower order sorts earlier, and CompositeTaskDecorator folds the list so the earliest
     * element ends up innermost. Peekaboot must be inside the propagating decorator, which
     * declares no order at all and therefore sorts last at LOWEST_PRECEDENCE.
     */
    @Test
    void sortsInsideAnUnorderedPropagatingDecorator() {
        contextRunner
                .withUserConfiguration(ObservationRegistryConfig.class, PropagatingDecoratorConfig.class)
                .run(context -> {
                    var decorators = context.getBeanProvider(TaskDecorator.class)
                            .orderedStream()
                            .toList();

                    assertThat(decorators)
                            .extracting(Object::getClass)
                            .containsExactly(AsyncTaskDecorator.class, ContextPropagatingTaskDecorator.class);
                });
    }

    @Configuration
    static class ObservationRegistryConfig {
        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }
    }

    @Configuration
    static class PropagatingDecoratorConfig {
        @Bean
        ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
            return new ContextPropagatingTaskDecorator();
        }
    }

    @Configuration
    static class UserDecoratorConfig {

        static final TaskDecorator DECORATOR = runnable -> runnable;

        @Bean
        TaskDecorator peekabootAsyncTaskDecorator() {
            return DECORATOR;
        }
    }
}
