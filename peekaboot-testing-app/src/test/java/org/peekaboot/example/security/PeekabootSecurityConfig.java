package org.peekaboot.example.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The worked example published on the website's <a
 * href="https://peekaboot.org/docs/security/">security page</a>, kept here as compiled,
 * executed code so the page cannot drift from something that works.
 * {@code SecuredPeekabootIntegrationTest} and {@code SecuredDashboardTest} are its proof;
 * change one and change the other.
 *
 * <p>Deliberately outside {@code org.peekaboot.testingapp} so {@code TestingApp}'s
 * component scan does not pick it up: the rest of this module's tests run with the servlet
 * security auto-configuration excluded (see {@code application-test.yml}), and a stray
 * {@link SecurityFilterChain} bean would fail their contexts. The two tests that want it
 * name it in {@code @SpringBootTest(classes = ...)} instead.
 */
@Configuration
public class PeekabootSecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain peekabootSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/peekaboot/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /**
     * Your application's own chain, whatever it already is. What matters is that it carries
     * no {@code @Order} at all, so it keeps Spring Security's default of
     * {@link Ordered#LOWEST_PRECEDENCE} and is evaluated last, as the catch-all. This sample
     * application has no access rules of its own, hence {@code permitAll}.
     */
    @Bean
    public SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    /**
     * Where {@code ROLE_ADMIN} comes from. An in-memory store with literal passwords is an
     * illustration, not a recommendation - replace it with whatever your application already
     * authenticates against.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager(
                User.withUsername("admin")
                        .password("{noop}admin-password")
                        .roles("ADMIN")
                        .build(),
                User.withUsername("user")
                        .password("{noop}user-password")
                        .roles("USER")
                        .build());
    }
}
