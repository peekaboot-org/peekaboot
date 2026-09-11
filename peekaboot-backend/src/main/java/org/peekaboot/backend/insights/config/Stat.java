package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a series derives one value per tick from its meters; serialised as the word the panel file uses. */
public enum Stat {
    VALUE("value"),
    RATE("rate"),
    AVG("avg"),
    MAX("max");

    private final String wireName;

    Stat(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
