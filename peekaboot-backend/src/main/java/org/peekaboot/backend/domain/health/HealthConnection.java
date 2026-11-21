package org.peekaboot.backend.domain.health;

import org.jspecify.annotations.Nullable;

/** What a health component connects to, read from configuration. */
public sealed interface HealthConnection permits DataSourceConnection, RedisConnection, MailConnection {

    /**
     * A host and port.
     *
     * @param hostname never a user or password
     */
    record Host(String hostname, @Nullable Integer port) {}
}
