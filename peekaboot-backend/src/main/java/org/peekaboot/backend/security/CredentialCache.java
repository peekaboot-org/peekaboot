package org.peekaboot.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which {@code Authorization} headers have already been verified, so the dashboard's
 * many XHRs per page cost one PBKDF2 derivation between them rather than one each.
 *
 * <p>Only successful verifications are remembered, so a wrong password always pays the full cost
 * and the cache buys an attacker nothing. Headers are keyed by their SHA-256, so the password
 * itself is not held in the map.
 */
public class CredentialCache {

    static final int MAX_ENTRIES = 64;

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Instant> verifiedUntil = new LruMap();

    public CredentialCache() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public CredentialCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized boolean isKnownGood(String authorizationHeader) {
        String key = key(authorizationHeader);
        Instant expiry = verifiedUntil.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry.isBefore(clock.instant())) {
            verifiedUntil.remove(key);
            return false;
        }
        return true;
    }

    public synchronized void remember(String authorizationHeader) {
        verifiedUntil.put(key(authorizationHeader), clock.instant().plus(ttl));
    }

    private static String key(String authorizationHeader) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder()
                    .encodeToString(digest.digest(authorizationHeader.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JDK this library runs on
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    /** Access-order so a lookup counts as use, evicting the entry nothing has re-verified lately. */
    private static final class LruMap extends LinkedHashMap<String, Instant> {

        private LruMap() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_ENTRIES;
        }
    }
}
