package org.peekaboot.backend.tracing.listener;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.EventListenerMarker;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/** Observes one call of an application's {@code @EventListener} method as a span of its own, while one is current. */
public class EventListenerObservationInterceptor implements MethodInterceptor {

    private final Supplier<ObservationRegistry> observationRegistry;

    public EventListenerObservationInterceptor(Supplier<ObservationRegistry> observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        ObservationRegistry registry = observationRegistry.get();
        // with nothing in scope a span would start a rootless trace
        if (registry.getCurrentObservation() == null) {
            return invocation.proceed();
        }
        Class<?> listenerClass = ClassUtils.getUserClass(
                AopUtils.getTargetClass(Objects.requireNonNull(invocation.getThis(), "a bean method has a target")));
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), listenerClass);
        return Observation.createNotStarted(EventListenerMarker.OBSERVATION_NAME, registry)
                .contextualName(listenerClass.getSimpleName() + "#" + method.getName())
                .lowCardinalityKeyValue(EventListenerMarker.EVENT_TYPE_TAG_KEY, eventType(method))
                .lowCardinalityKeyValue(EventListenerMarker.LISTENER_CLASS_TAG_KEY, listenerClass.getName())
                .lowCardinalityKeyValue(EventListenerMarker.LISTENER_METHOD_TAG_KEY, method.getName())
                .observeChecked(invocation::proceed);
    }

    /** The annotation's event classes narrow what the listener hears, and a parameterless listener has nothing else. */
    private static String eventType(Method method) {
        EventListener listener =
                Objects.requireNonNull(AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class));
        Class<?>[] eventClasses = listener.classes().length > 0 ? listener.classes() : method.getParameterTypes();
        return Arrays.stream(eventClasses).map(Class::getSimpleName).collect(Collectors.joining(","));
    }
}
