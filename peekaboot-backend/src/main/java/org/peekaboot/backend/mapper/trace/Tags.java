package org.peekaboot.backend.mapper.trace;

import java.util.Map;

/** The lookup every convention-aware reader in this package shares: the first key that carries a value. */
final class Tags {

    private Tags() {}

    static String first(Map<String, String> tags, String... keys) {
        for (String key : keys) {
            String value = tags.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
