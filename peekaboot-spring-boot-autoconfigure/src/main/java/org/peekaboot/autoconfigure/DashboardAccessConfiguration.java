package org.peekaboot.autoconfigure;

import org.peekaboot.backend.security.DashboardAccess;
import org.peekaboot.backend.security.DashboardAccessInterceptor;
import org.peekaboot.backend.security.DashboardAuthenticationFilter;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The one {@link DashboardAccess} every enforcement point asks, and the handler-level check that
 * asks it again for Peekaboot's own handlers. Spring MVC is optional, so only {@link HandlerLevel}
 * names its types.
 */
@Configuration(proxyBeanMethods = false)
class DashboardAccessConfiguration {

    /**
     * The registered guard's own check, so an application that replaced the guard is asked for
     * its credentials at both points and never for Peekaboot's. Found by bean definition, for
     * the reason the posture's own lookup in {@link PeekabootSecurityAutoConfiguration} gives.
     */
    @Bean
    @ConditionalOnMissingBean
    DashboardAccess peekabootDashboardAccess(ListableBeanFactory beanFactory) {
        String[] guards = beanFactory.getBeanNamesForType(PeekabootSecurityAutoConfiguration.GUARD_TYPE, true, false);
        if (guards.length == 0) {
            return DashboardAccess.open();
        }
        FilterRegistrationBean<?> guard = beanFactory.getBean(guards[0], FilterRegistrationBean.class);
        if (guard.getFilter() instanceof DashboardAuthenticationFilter filter) {
            return filter.access();
        }
        // admitting everything here would leave the handlers open behind a guard that is declared
        throw new IllegalStateException("The guard registration '" + guards[0]
                + "' holds no DashboardAuthenticationFilter, so Peekaboot cannot tell what it asks.");
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class HandlerLevel {

        // matched by name: a type check on WebMvcConfigurer would let any of the
        // application's own configurers back this registration off
        @Bean
        @ConditionalOnMissingBean(name = "peekabootDashboardAccessConfigurer")
        WebMvcConfigurer peekabootDashboardAccessConfigurer(DashboardAccess access) {
            return new WebMvcConfigurer() {
                // no path pattern, so every handler mapping carries it, and first, so nothing runs ahead of the check
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new DashboardAccessInterceptor(access))
                            .order(Ordered.HIGHEST_PRECEDENCE);
                }
            };
        }
    }
}
