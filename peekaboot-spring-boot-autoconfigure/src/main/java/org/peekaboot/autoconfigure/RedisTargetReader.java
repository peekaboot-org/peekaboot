package org.peekaboot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.health.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** Reads the server a {@link RedisConnectionFactory} of one client library is configured for. */
interface RedisTargetReader {

    /** {@code null} for a factory this reader does not know. Never opens a connection. */
    @Nullable
    RedisConnection read(RedisConnectionFactory factory);
}
