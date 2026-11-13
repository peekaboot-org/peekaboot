package org.peekaboot.fixtures;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/** A host that maps a second DispatcherServlet over the same context, so every handler answers under its prefix too. */
@Configuration(proxyBeanMethods = false)
public class SecondDispatcherServletConfig {

    public static final String PREFIX = "/second";

    @Bean
    ServletRegistrationBean<DispatcherServlet> secondDispatcherServlet(WebApplicationContext context) {
        ServletRegistrationBean<DispatcherServlet> registration =
                new ServletRegistrationBean<>(new DispatcherServlet(context), PREFIX + "/*");
        // the name deduced from the servlet's class is the one Boot's own registration holds
        registration.setName("secondDispatcherServlet");
        return registration;
    }
}
