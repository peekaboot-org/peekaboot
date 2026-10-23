package org.peekaboot.backend.insights.config;

import com.fasterxml.jackson.annotation.JsonValue;

/** How the Overview renders a tile's value; serialised as the word the panel file and the frontend use. */
public enum TileFormat {
    DURATION,
    DATETIME,
    BYTES,
    COUNT;

    @JsonValue
    public String wireName() {
        return WireNames.of(this);
    }
}
