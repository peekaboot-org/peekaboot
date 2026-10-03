package org.peekaboot.backend.tracing.listener;

import io.micrometer.observation.annotation.Observed;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/** Picks the {@code @EventListener} methods Peekaboot observes, and skips any class that proxying would break. */
final class EventListenerPointcut extends StaticMethodMatcherPointcut {

    private static final Logger log = LoggerFactory.getLogger(EventListenerPointcut.class);

    // the library's packages, not all of org.peekaboot: the testing app lives there too
    private static final List<String> SKIPPED_PACKAGE_PREFIXES =
            List.of("org.peekaboot.backend.", "org.peekaboot.autoconfigure.", "org.springframework.");

    EventListenerPointcut() {
        setClassFilter(EventListenerPointcut::isObservable);
    }

    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        Method listenerMethod = AopUtils.getMostSpecificMethod(method, targetClass);
        return isListener(listenerMethod)
                && isInterceptable(listenerMethod)
                && !AnnotatedElementUtils.hasAnnotation(listenerMethod, Observed.class);
    }

    private static boolean isObservable(Class<?> candidate) {
        Class<?> userClass = ClassUtils.getUserClass(candidate);
        if (isSkipped(userClass) || !AnnotationUtils.isCandidateClass(userClass, EventListener.class)) {
            return false;
        }
        Method[] listeners = ReflectionUtils.getUniqueDeclaredMethods(userClass, EventListenerPointcut::isListener);
        if (listeners.length == 0 || AnnotatedElementUtils.hasAnnotation(userClass, Observed.class)) {
            return false;
        }
        String proxyingObstacle = proxyingObstacle(userClass);
        if (proxyingObstacle != null) {
            log.debug("Not observing the @EventListener methods of {}: {}", userClass.getName(), proxyingObstacle);
            return false;
        }
        if (!Arrays.stream(listeners).allMatch(EventListenerPointcut::isInterceptable)) {
            log.debug(
                    "Not observing the @EventListener methods of {}: a private listener method cannot sit behind a proxy",
                    userClass.getName());
            return false;
        }
        return true;
    }

    private static @Nullable String proxyingObstacle(Class<?> userClass) {
        if (Modifier.isFinal(userClass.getModifiers())) {
            return "the class is final";
        }
        if (userClass.isSealed()) {
            return "the class is sealed";
        }
        if (Arrays.stream(userClass.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()))) {
            return "the class has only private constructors";
        }
        Method[] finalMethods = ReflectionUtils.getUniqueDeclaredMethods(
                userClass, ReflectionUtils.USER_DECLARED_METHODS.and(EventListenerPointcut::runsOnTheProxy));
        if (finalMethods.length > 0) {
            return "the final method " + finalMethods[0].getName()
                    + "() would run on the proxy instance, whose fields were never set";
        }
        return null;
    }

    private static boolean runsOnTheProxy(Method method) {
        int modifiers = method.getModifiers();
        return Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers);
    }

    private static boolean isSkipped(Class<?> userClass) {
        return SKIPPED_PACKAGE_PREFIXES.stream().anyMatch(userClass.getName()::startsWith);
    }

    /** Static listener methods are called without the bean, so no proxy is ever in their way. */
    private static boolean isListener(Method method) {
        return !Modifier.isStatic(method.getModifiers())
                && AnnotatedElementUtils.hasAnnotation(method, EventListener.class);
    }

    private static boolean isInterceptable(Method method) {
        return !Modifier.isPrivate(method.getModifiers());
    }
}
