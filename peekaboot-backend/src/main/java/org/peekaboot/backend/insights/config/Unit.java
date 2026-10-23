package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a panel's or series' values measure; serialised as the word the panel file and the frontend use. */
public enum Unit {
    BYTES,
    PERCENT,
    MILLIS,
    COUNT,
    PERSEC,
    BYTES_PERSEC;

    @JsonValue
    public String wireName() {
        return WireNames.of(this);
    }
}
