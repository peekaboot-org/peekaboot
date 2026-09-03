package org.peekaboot.backend.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.boot.info.InfoProperties;

/**
 * The entries of Spring's build or git info that the lifecycle log keeps, in a stable
 * order: the ones {@link LifecycleEvents} and {@link LifecycleRuns} read, and no others.
 *
 * <p>The generating plugins emit far more than that - a remote URL, which for an HTTPS
 * remote can carry the token it was cloned with, and the building user's mail address -
 * and the log is written into a developer's home directory, where it outlives the process
 * that wrote it.
 */
final class InfoEntries {

    /**
     * Keys from both sources in one set: {@code version} and {@code time} are
     * {@code BuildProperties}' spellings, the rest {@code GitProperties}', and neither map
     * carries the other's. {@code commit.id.full} is what git-commit-id writes under
     * {@code commitIdGenerationMode=full}, where {@code commit.id} is absent.
     */
    private static final Set<String> KEPT_KEYS = Set.of(
            "version",
            "time",
            "branch",
            "commit.id",
            "commit.id.full",
            "commit.id.abbrev",
            "build.version",
            "build.time");

    private InfoEntries() {}

    static Map<String, String> of(InfoProperties properties) {
        if (properties == null) {
            return Map.of();
        }
        Map<String, String> sorted = new TreeMap<>();
        properties.forEach(entry -> {
            if (KEPT_KEYS.contains(entry.getKey())) {
                sorted.put(entry.getKey(), entry.getValue());
            }
        });
        return new LinkedHashMap<>(sorted);
    }
}
