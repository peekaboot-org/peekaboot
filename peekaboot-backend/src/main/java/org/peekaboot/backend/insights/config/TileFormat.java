package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** How the Overview renders a tile's value; serialised as the word the panel file and the frontend use. */
public enum TileFormat {
    DURATION("duration"),
    DATETIME("datetime"),
    BYTES("bytes"),
    COUNT("count");

    private final String wireName;

    TileFormat(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
