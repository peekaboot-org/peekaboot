package org.peekaboot.backend.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.info.InfoProperties;

/**
 * The entries of Spring's build or git info that the lifecycle log keeps, in a stable
 * order: the ones {@link BuildFacts} reads for {@link LifecycleEvents} and
 * {@link LifecycleRuns}, and no others. Both sources share one key set, since neither
 * map carries the other's keys.
 *
 * <p>The generating plugins emit far more than that - a remote URL, which for an HTTPS
 * remote can carry the token it was cloned with, and the building user's mail address -
 * and the log is written into a developer's home directory, where it outlives the process
 * that wrote it.
 */
final class InfoEntries {

    private InfoEntries() {}

    static Map<String, String> of(InfoProperties properties) {
        if (properties == null) {
            return Map.of();
        }
        Map<String, String> sorted = new TreeMap<>();
        properties.forEach(entry -> {
            if (BuildFacts.READ_KEYS.contains(entry.getKey())) {
                sorted.put(entry.getKey(), entry.getValue());
            }
        });
        return new LinkedHashMap<>(sorted);
    }
}
