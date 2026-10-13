package org.peekaboot.autoconfigure;

import java.util.Map;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.SimpleAutowireCandidateResolver;

/** The application's beans of one type, found the way Boot's db health contributor finds them. */
final class ApplicationBeans {

    private ApplicationBeans() {}

    /** Includes {@code defaultCandidate = false} beans, which plain {@code Map} injection drops. */
    static <T> Map<String, T> ofType(ConfigurableListableBeanFactory beanFactory, Class<T> type) {
        return SimpleAutowireCandidateResolver.resolveAutowireCandidates(beanFactory, type, false, true);
    }
}
