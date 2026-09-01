package org.peekaboot.backend.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PeekabootWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/peekaboot/ui/**")
                .addResourceLocations("classpath:/static/peekaboot/ui/")
                .setCacheControl(CacheControl.noCache());
    }

    /** First in the list, so a Peekaboot type never reaches the application's own JSON converter. */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.addFirst(new PeekabootJsonMessageConverter());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/peekaboot", "/peekaboot/ui/dashboard/index.html");
        registry.addRedirectViewController("/peekaboot/", "/peekaboot/ui/dashboard/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiSecurityHeadersInterceptor()).addPathPatterns("/peekaboot/api/**");
    }
}
