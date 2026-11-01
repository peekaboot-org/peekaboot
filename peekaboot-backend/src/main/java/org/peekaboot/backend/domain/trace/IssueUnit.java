package org.peekaboot.backend.domain.trace;

import com.fasterxml.jackson.annotation.JsonValue;

/** The unit of an issue's value and threshold, serialised as the lowercase word an agent reads. */
public enum IssueUnit {
    MS("ms"),
    QUERIES("queries");

    private final String wireName;

    IssueUnit(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
