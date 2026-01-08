package net.osslabz.peekaboot.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PeekabootWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/peekaboot/ui/**")
                .addResourceLocations("classpath:/static/peekaboot/ui/")
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/peekaboot", "/peekaboot/ui/dashboard/index.html");
        registry.addRedirectViewController("/peekaboot/", "/peekaboot/ui/dashboard/index.html");
    }
}
