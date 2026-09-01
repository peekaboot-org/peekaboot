package org.peekaboot.backend.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.info.InfoProperties;

/** Reads every entry out of Spring's build or git info, in a stable order. */
final class InfoEntries {

    private InfoEntries() {}

    static Map<String, String> of(InfoProperties properties) {
        if (properties == null) {
            return Map.of();
        }
        Map<String, String> sorted = new TreeMap<>();
        properties.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return new LinkedHashMap<>(sorted);
    }
}
