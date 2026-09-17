package org.peekaboot.autoconfigure;

import java.util.List;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;

/**
 * The application's own packages, so its frames can be told from the framework's - shared
 * because both the error page and the trace store's stack-trace folding need the same answer.
 */
final class ApplicationPackages {

    private ApplicationPackages() {}

    /** Empty where the application registered none, rather than a lookup {@code AutoConfigurationPackages} would reject. */
    static List<String> resolve(BeanFactory beanFactory) {
        return AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
    }
}
