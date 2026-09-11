package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CredentialCacheTest {

    private static final String HEADER = "Basic b3JkZXJzLWFkbWluOnMzY3JldA==";

    @Test
    void isKnownGood_isFalseUntilTheHeaderIsRemembered() {
        var cache = new CredentialCache(Duration.ofMinutes(5), Clock.systemUTC());

        assertThat(cache.isKnownGood(HEADER)).isFalse();

        cache.remember(HEADER);

        assertThat(cache.isKnownGood(HEADER)).isTrue();
    }

    @Test
    void isKnownGood_distinguishesHeaders() {
        var cache = new CredentialCache(Duration.ofMinutes(5), Clock.systemUTC());
        cache.remember(HEADER);

        assertThat(cache.isKnownGood("Basic b3RoZXI6d3Jvbmc=")).isFalse();
    }

    @Test
    void isKnownGood_forgetsAnEntryOnceItsTtlHasPassed() {
        var clock = new MovableClock(Instant.parse("2026-09-11T08:00:00Z"));
        var cache = new CredentialCache(Duration.ofMinutes(5), clock);
        cache.remember(HEADER);

        clock.advance(Duration.ofMinutes(4));
        assertThat(cache.isKnownGood(HEADER)).isTrue();

        clock.advance(Duration.ofMinutes(2));
        assertThat(cache.isKnownGood(HEADER)).isFalse();
    }

    @Test
    void remember_evictsTheLeastRecentlyUsedEntryAtTheBound() {
        var cache = new CredentialCache(Duration.ofMinutes(5), Clock.systemUTC());
        for (int i = 0; i < CredentialCache.MAX_ENTRIES; i++) {
            cache.remember("Basic header-" + i);
        }

        // touching header-0 makes header-1 the least recently used, not header-0
        assertThat(cache.isKnownGood("Basic header-0")).isTrue();

        cache.remember("Basic header-" + CredentialCache.MAX_ENTRIES);

        assertThat(cache.isKnownGood("Basic header-1")).isFalse();
        assertThat(cache.isKnownGood("Basic header-0")).isTrue();
        assertThat(cache.isKnownGood("Basic header-" + CredentialCache.MAX_ENTRIES))
                .isTrue();
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
