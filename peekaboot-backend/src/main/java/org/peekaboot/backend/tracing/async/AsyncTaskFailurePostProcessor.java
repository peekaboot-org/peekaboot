package org.peekaboot.backend.tracing.async;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;

/**
 * Puts {@link AsyncTaskFailureInterceptor} into every proxy Spring made for {@code @Async},
 * directly behind Spring's own async advisor. There it runs on the executor thread, inside the
 * {@code async task} span, and outside every advisor that sits behind Spring's. It reuses that advisor's
 * pointcut, so it covers exactly the methods Spring hands off, a custom
 * {@code @EnableAsync(annotation = ...)} included.
 *
 * <p>Deliberately not {@code Ordered}: Spring registers unordered post-processors after every
 * ordered one, so this sees each bean after {@code AsyncAnnotationBeanPostProcessor} has, whatever
 * order the application gives {@code @EnableAsync}. It never creates a proxy, so an interface
 * proxy stays one. {@code @EnableAsync(mode = ASPECTJ)} weaves instead of proxying, and a frozen
 * proxy takes no further advisor; failures of those methods are not recorded on the span.
 */
public final class AsyncTaskFailurePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskFailurePostProcessor.class);

    private final AsyncTaskFailureInterceptor interceptor = new AsyncTaskFailureInterceptor();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof Advised proxy)) {
            return bean;
        }
        Advisor[] advisors = proxy.getAdvisors();
        AsyncAnnotationAdvisor asyncAdvisor = asyncAdvisorOf(advisors);
        if (asyncAdvisor == null || alreadyJoined(advisors)) {
            return bean;
        }
        if (proxy.isFrozen()) {
            log.debug(
                    "Failures of {}'s @Async methods are not recorded on their async task spans: its proxy is frozen",
                    beanName);
            return bean;
        }
        proxy.addAdvisor(
                proxy.indexOf(asyncAdvisor) + 1, new DefaultPointcutAdvisor(asyncAdvisor.getPointcut(), interceptor));
        return bean;
    }

    private static @Nullable AsyncAnnotationAdvisor asyncAdvisorOf(Advisor[] advisors) {
        return Arrays.stream(advisors)
                .filter(AsyncAnnotationAdvisor.class::isInstance)
                .map(AsyncAnnotationAdvisor.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static boolean alreadyJoined(Advisor[] advisors) {
        return Arrays.stream(advisors).anyMatch(advisor -> advisor.getAdvice() instanceof AsyncTaskFailureInterceptor);
    }
}
