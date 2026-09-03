package org.peekaboot.backend.domain.trace;

import com.fasterxml.jackson.annotation.JsonValue;

/** How serious a {@link SpanIssue} is; serialised as the lowercase word the frontend reads. */
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
