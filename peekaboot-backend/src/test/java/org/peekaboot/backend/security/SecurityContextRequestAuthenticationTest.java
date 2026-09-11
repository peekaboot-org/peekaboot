package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityContextRequestAuthenticationTest {

    private final RequestAuthentication requestAuthentication = new SecurityContextRequestAuthentication();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void alreadyAuthenticated_isFalseWithAnEmptyContext() {
        assertThat(requestAuthentication.alreadyAuthenticated()).isFalse();
    }

    /** An anonymous token is Spring Security saying "nobody", not "somebody". */
    @Test
    void alreadyAuthenticated_isFalseForAnAnonymousToken() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(requestAuthentication.alreadyAuthenticated()).isFalse();
    }

    @Test
    void alreadyAuthenticated_isTrueForAnAuthenticatedToken() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated("ops", null, List.of()));

        assertThat(requestAuthentication.alreadyAuthenticated()).isTrue();
    }

    /**
     * Starts from a non-empty context (an anonymous token, as Spring Security's own filter
     * leaves it) so the assertion can tell a restored context from any other non-authenticating
     * state, which {@code alreadyAuthenticated() == false} alone cannot.
     */
    @Test
    void record_thenClear_leavesTheContextAsItFoundIt() {
        var anonymous = new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anonymous);

        requestAuthentication.record("orders-admin");
        assertThat(requestAuthentication.alreadyAuthenticated()).isTrue();

        requestAuthentication.clear();

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(anonymous);
    }

    @Test
    void neverAuthenticated_alwaysSaysNo() {
        var absent = new NeverAuthenticated();

        absent.record("orders-admin");

        assertThat(absent.alreadyAuthenticated()).isFalse();
    }
}
