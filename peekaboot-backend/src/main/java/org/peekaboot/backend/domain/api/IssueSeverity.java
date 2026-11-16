package org.peekaboot.backend.domain.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** How serious a finding is, on a trace issue or an insights problem; serialised as the lowercase word the frontend reads. */
public enum IssueSeverity {
    WARNING("warning"),
    ERROR("error");

    private final String wireName;

    IssueSeverity(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
