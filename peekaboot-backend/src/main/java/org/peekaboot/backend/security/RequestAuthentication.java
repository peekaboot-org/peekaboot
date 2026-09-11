package org.peekaboot.backend.security;

/**
 * Whether something other than Peekaboot has already authenticated the request in hand.
 *
 * <p>This is what lets the dashboard guard compose with an application's own security instead of
 * competing with it: a contributed {@code SecurityFilterChain} cannot express "last resort" -
 * {@code FilterChainProxy} is first-match-wins, so it would either shadow working rules or never
 * be reached - while the request itself, once the chain has run, answers the question exactly.
 */
public interface RequestAuthentication {

    boolean alreadyAuthenticated();

    /** Publishes the guard's own decision, so anything downstream sees a real principal. */
    void record(String username);

    /** Undoes {@link #record}, whatever the filter chain did in between. */
    void clear();
}
