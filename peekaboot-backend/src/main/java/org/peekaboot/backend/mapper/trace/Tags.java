package org.peekaboot.backend.mapper.trace;

import java.util.Map;

/** The lookups every convention-aware reader in this package shares. */
final class Tags {

    private Tags() {}

    /** The value of the first of {@code keys} that carries one. */
    static String first(Map<String, String> tags, String... keys) {
        for (String key : keys) {
            String value = tags.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Whether any tag's key starts with one of {@code prefixes}, the way a tag family is recognised. */
    static boolean anyKeyStartsWith(Map<String, String> tags, String... prefixes) {
        for (String key : tags.keySet()) {
            for (String prefix : prefixes) {
                if (key.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
