package org.peekaboot.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import org.peekaboot.backend.tracing.async.AsyncTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;

@AutoConfiguration(after = ObservationAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(TaskDecorator.class)
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ENABLED, matchIfMissing = true)
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ASYNC, matchIfMissing = true)
public class AsyncInstrumentationAutoConfiguration {

    /**
     * Below {@code LOWEST_PRECEDENCE} so the decorator sorts before the unordered
     * {@code ContextPropagatingTaskDecorator} and therefore runs inside the restored context;
     * near it so an application decorator with an ordinary explicit order runs inside
     * Peekaboot's observation and its work is attributed to the async span.
     */
    private static final int ASYNC_DECORATOR_ORDER = Ordered.LOWEST_PRECEDENCE - 1000;

    private static final Logger log = LoggerFactory.getLogger(AsyncInstrumentationAutoConfiguration.class);

    // matched by name: a type check on TaskDecorator would let the application's own
    // ContextPropagatingTaskDecorator back this registration off, which is the one
    // configuration the decorator exists for
    @Bean
    @ConditionalOnMissingBean(name = "peekabootAsyncTaskDecorator")
    @Order(ASYNC_DECORATOR_ORDER)
    public TaskDecorator peekabootAsyncTaskDecorator(ObservationRegistry observationRegistry) {
        log.debug("Peekaboot AsyncTaskDecorator registered for async task span capture");
        return new AsyncTaskDecorator(observationRegistry);
    }
}
