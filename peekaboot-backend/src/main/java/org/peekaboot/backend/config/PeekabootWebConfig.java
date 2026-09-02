package org.peekaboot.backend.config;

import org.springframework.http.CacheControl;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

public class PeekabootWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(PeekabootPaths.BASE_PATH + "/ui/**")
                .addResourceLocations("classpath:" + PeekabootPaths.CLASSPATH_ROOT + "/ui/")
                .setCacheControl(CacheControl.noCache());
    }

    /**
     * Registered as a custom converter, which the builder places ahead of every converter
     * an application configures, so a Peekaboot type never reaches the application's own
     * JSON converter.
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new PeekabootJsonMessageConverter());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        String dashboard = PeekabootPaths.BASE_PATH + "/ui/dashboard/index.html";
        registry.addRedirectViewController(PeekabootPaths.BASE_PATH, dashboard);
        registry.addRedirectViewController(PeekabootPaths.BASE_PATH + "/", dashboard);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiSecurityHeadersInterceptor())
                .addPathPatterns(PeekabootPaths.BASE_PATH + "/api/**");
    }
}
