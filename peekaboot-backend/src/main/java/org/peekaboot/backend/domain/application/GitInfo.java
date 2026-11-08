package org.peekaboot.backend.domain.application;

import java.time.Instant;

/**
 * The git facts the Overview shows, and the only ones that leave the application's
 * git.properties: a remote URL can carry the token it was cloned with and the committer's mail
 * address is personal data. Nested as the dashboard reads them.
 */
public record GitInfo(String branch, Commit commit) {

    public record Commit(String id, Instant time) {}
}
