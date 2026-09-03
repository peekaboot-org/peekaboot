package org.peekaboot.backend.masking;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Masks a flat key/value tag collection - HTTP headers, span tags, Micrometer meter tags -
 * by running each value through {@link MaskingEngine#mask(String, String)}. A tag whose key
 * is sensitive-shaped has its whole value replaced; every other tag's value still runs
 * through the value-pattern rules, so a credential embedded in an otherwise ordinary tag
 * (a bearer token inside an {@code http.url} tag, say) is still caught without disturbing
 * a tag like {@code http.method} whose key and value are both innocuous.
 *
 * <p>Shared by every site that carries this exact shape - {@code Map<String, String>}
 * keyed by tag/header name - rather than each reimplementing the same per-entry loop.
 */
public final class TagMasker {

    private final MaskingEngine maskingEngine;

    public TagMasker(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
    }

    public Map<String, String> mask(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return tags;
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            result.put(entry.getKey(), maskingEngine.mask(entry.getKey(), entry.getValue()));
        }
        return result;
    }
}
