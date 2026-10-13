package org.peekaboot.backend.mapper.trace;

import org.jspecify.annotations.Nullable;

/** Lays a captured statement out for reading. Moves whitespace only; what the statement says never changes. */
public interface SqlFormatter {

    /** Lays nothing out, so every statement's {@code formatted} is null; what runs without Hibernate. */
    SqlFormatter NONE = sql -> null;

    /** {@code sql} laid out for reading, or null where this formatter offers no layout. */
    @Nullable
    String format(String sql);
}
