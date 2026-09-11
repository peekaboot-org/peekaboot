package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a panel draws its series; serialised as the word the panel file and the frontend use. */
public enum Chart {
    LINE("line"),
    BARS("bars"),
    /** The first series as bars, the rest as lines. */
    BARS_LINE("bars-line");

    private final String wireName;

    Chart(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
