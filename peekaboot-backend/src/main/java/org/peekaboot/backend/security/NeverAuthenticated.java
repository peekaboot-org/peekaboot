package org.peekaboot.backend.security;

/** The answer without Spring Security: nothing else can have authenticated the request. */
public class NeverAuthenticated implements RequestAuthentication {

    @Override
    public boolean alreadyAuthenticated() {
        return false;
    }

    @Override
    public void record(String username) {
        // nowhere to publish it, and nothing downstream that would read it
    }

    @Override
    public void clear() {
        // nothing was recorded
    }
}
