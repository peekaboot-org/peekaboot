package org.peekaboot.backend.security;

import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** The answer where Spring Security is on the classpath. */
public class SecurityContextRequestAuthentication implements RequestAuthentication {

    // record() and clear() always run on the same thread within one request, but the bean
    // itself is a singleton shared across every concurrently handled request.
    private static final ThreadLocal<Authentication> PREVIOUS_AUTHENTICATION = new ThreadLocal<>();

    @Override
    public boolean alreadyAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    @Override
    public void record(String username) {
        SecurityContext context = SecurityContextHolder.getContext();
        PREVIOUS_AUTHENTICATION.set(context.getAuthentication());
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
    }

    @Override
    public void clear() {
        SecurityContextHolder.getContext().setAuthentication(PREVIOUS_AUTHENTICATION.get());
        PREVIOUS_AUTHENTICATION.remove();
    }
}
