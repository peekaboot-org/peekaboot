package org.peekaboot.fixtures;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** A host that versions its REST API by a path prefix, which moves Peekaboot's controllers along with its own. */
@Configuration(proxyBeanMethods = false)
public class PrefixedRestControllersConfig implements WebMvcConfigurer {

    public static final String PREFIX = "/api";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(PREFIX, HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
