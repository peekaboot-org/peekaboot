package org.peekaboot.backend.tracing.listener;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.tags.EventListenerMarker;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/** Observes one call of an application's {@code @EventListener} method as a span of its own, while one is current. */
class EventListenerObservationInterceptor implements MethodInterceptor {

    private final Supplier<ObservationRegistry> observationRegistry;
    private final Map<ListenerMethod, ListenerSpanLabels> spanLabels = new ConcurrentHashMap<>();

    EventListenerObservationInterceptor(Supplier<ObservationRegistry> observationRegistry) {
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
        // keyed by bean class too: beans inheriting one listener method share its Method object
        ListenerSpanLabels labels = spanLabels.computeIfAbsent(
                new ListenerMethod(invocation.getMethod(), listenerClass), ListenerSpanLabels::of);
        return Observation.createNotStarted(EventListenerMarker.OBSERVATION_NAME, registry)
                .contextualName(labels.contextualName())
                .lowCardinalityKeyValue(EventListenerMarker.EVENT_TYPE_TAG_KEY, labels.eventType())
                .lowCardinalityKeyValue(EventListenerMarker.LISTENER_CLASS_TAG_KEY, labels.listenerClass())
                .lowCardinalityKeyValue(EventListenerMarker.LISTENER_METHOD_TAG_KEY, labels.method())
                .observeChecked(invocation::proceed);
    }

    private record ListenerMethod(Method invoked, Class<?> listenerClass) {}

    /** What one listener method's spans are called and tagged with. */
    private record ListenerSpanLabels(String contextualName, String eventType, String listenerClass, String method) {

        static ListenerSpanLabels of(ListenerMethod key) {
            Method method = AopUtils.getMostSpecificMethod(key.invoked(), key.listenerClass());
            return new ListenerSpanLabels(
                    key.listenerClass().getSimpleName() + "#" + method.getName(),
                    eventType(method),
                    key.listenerClass().getName(),
                    method.getName());
        }

        /** The annotation's event classes narrow what the listener hears, and a parameterless listener has nothing else. */
        private static String eventType(Method method) {
            EventListener listener =
                    Objects.requireNonNull(AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class));
            Class<?>[] eventClasses = listener.classes().length > 0 ? listener.classes() : method.getParameterTypes();
            return Arrays.stream(eventClasses).map(Class::getSimpleName).collect(Collectors.joining(","));
        }
    }
}
