package org.peekaboot.autoconfigure;

import java.util.Arrays;
import java.util.Objects;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keeps Peekaboot's own endpoints, and the schemas only they use, out of the OpenAPI document
 * springdoc builds for the application. Hiding the controllers does what filtering paths would
 * not: springdoc never reads their types, so no schema is left behind.
 */
@AutoConfiguration(after = PeekabootAutoConfiguration.class)
@ConditionalOnPeekabootServlet
@ConditionalOnClass(SpringDocUtils.class)
public class PeekabootOpenApiAutoConfiguration {

    @Bean
    PeekabootControllersHiddenFromOpenApi peekabootControllersHiddenFromOpenApi(ListableBeanFactory beanFactory) {
        return new PeekabootControllersHiddenFromOpenApi(beanFactory);
    }

    /**
     * Hides the controllers once every singleton exists. A {@link SmartInitializingSingleton} stays
     * eager under {@code spring.main.lazy-initialization}, where a bean nothing injects is never made.
     */
    static final class PeekabootControllersHiddenFromOpenApi implements SmartInitializingSingleton {

        /** Not {@code org.peekaboot} as a whole, so a host's own controllers under it stay documented. */
        private static final String BACKEND_PACKAGE = "org.peekaboot.backend.";

        private final ListableBeanFactory beanFactory;

        private PeekabootControllersHiddenFromOpenApi(ListableBeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public void afterSingletonsInstantiated() {
            // getType(name, false): a lazy FactoryBean is not created just to learn its type
            Class<?>[] controllers = Arrays.stream(beanFactory.getBeanDefinitionNames())
                    .map(name -> beanFactory.getType(name, false))
                    .filter(Objects::nonNull)
                    .map(ClassUtils::getUserClass)
                    .filter(type -> type.getName().startsWith(BACKEND_PACKAGE))
                    .filter(type -> AnnotatedElementUtils.hasAnnotation(type, RestController.class))
                    .toArray(Class<?>[]::new);
            SpringDocUtils.getConfig().addHiddenRestControllers(controllers);
        }
    }
}
