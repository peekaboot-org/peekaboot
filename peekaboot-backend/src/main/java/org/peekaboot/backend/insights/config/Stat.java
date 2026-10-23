package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a series derives one value per tick from its meters; serialised as the word the panel file uses. */
public enum Stat {
    VALUE,
    RATE,
    AVG,
    MAX;

    @JsonValue
    public String wireName() {
        return WireNames.of(this);
    }
}
