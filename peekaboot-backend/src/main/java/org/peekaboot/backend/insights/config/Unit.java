package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a panel's or series' values measure; serialised as the word the panel file and the frontend use. */
public enum Unit {
    BYTES("bytes"),
    PERCENT("percent"),
    MILLIS("millis"),
    COUNT("count"),
    PERSEC("persec"),
    BYTES_PERSEC("bytes-persec");

    private final String wireName;

    Unit(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
