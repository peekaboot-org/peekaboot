package org.peekaboot.backend.security;

import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The answer where Spring Security is on the classpath. */
public class SecurityContextRequestAuthentication implements RequestAuthentication {

    @Override
    public boolean alreadyAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    @Override
    public void record(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(username, null, List.of()));
    }

    @Override
    public void clear() {
        SecurityContextHolder.clearContext();
    }
}
