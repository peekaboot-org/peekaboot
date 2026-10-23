package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a panel draws its series; serialised as the word the panel file and the frontend use. */
public enum Chart {
    LINE,
    BARS,
    /** The first series as bars, the rest as lines. */
    BARS_LINE;

    @JsonValue
    public String wireName() {
        return WireNames.of(this);
    }
}
