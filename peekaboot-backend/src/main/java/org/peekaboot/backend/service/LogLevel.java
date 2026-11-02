package org.peekaboot.backend.service;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.text.EnumNames;

/** Logback's levels, lowest first, so the log search's {@code level} keeps a level and every one above it. */
enum LogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    /** The level a captured event names; null for a name Logback never delivers. */
    @Nullable
    static LogLevel of(String captured) {
        return EnumNames.byName(LogLevel.class, captured, null);
    }
}
