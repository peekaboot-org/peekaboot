package org.peekaboot.backend.domain.application;

/**
 * The git facts the Overview shows, and the only ones that leave {@code info.git}: a remote
 * URL can carry the token it was cloned with and the committer's mail address is personal
 * data. Nested like the actuator's own {@code git.commit.id} so the wire shape is unchanged.
 */
public record GitInfo(String branch, Commit commit) {

    public record Commit(String id, String time) {}
}
