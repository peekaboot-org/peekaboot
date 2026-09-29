package org.peekaboot.backend.mapper.trace;

/** Lays a captured statement out for reading. Moves whitespace only; what the statement says never changes. */
public interface SqlFormatter {

    String format(String sql);
}
