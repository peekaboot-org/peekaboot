package org.peekaboot.backend.masking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Masks an arbitrary JSON-shaped tree (nested Map/List/scalar, the shape Jackson produces
 * when binding to {@code Object}) - for payloads with no fixed schema, where masking can't
 * dispatch to a domain mapper the way a typed field can (a consuming app's custom
 * {@code HealthIndicator} details, or the raw actuator payload's {@code beans}/{@code
 * conditions}/{@code mappings}/{@code sbom} endpoints).
 *
 * <p>A sensitive key replaces its entire value regardless of shape - generalising
 * {@link MaskingEngine#mask(String, String)}'s whole-value-replace rule to a tree, e.g. a
 * {@code connectionParams.password} entry shaped {@code {value, source}} becomes the single
 * string {@code "******"}. An innocuous key recurses into Maps/Lists and runs the
 * value-pattern rules on String leaves via {@link MaskingEngine#maskValue(String)}.
 *
 * <p>A nested key is judged by its dotted path from the root ({@code session.cookie}), not
 * by its last segment, so the whole-key rules keep meaning the entire key; list elements
 * share their list's path.
 */
public final class TreeMasker {

    private final MaskingEngine maskingEngine;

    public TreeMasker(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
    }

    public Object mask(Object node) {
        return maskNode(null, node);
    }

    /** Bypasses masking entirely when {@code unmask} is true (see {@link MaskingEngine#mask(String, String, boolean)}). */
    public Object mask(Object node, boolean unmask) {
        return unmask ? node : mask(node);
    }

    /**
     * Same as {@link #mask(Object)}, except {@code key} is checked against
     * {@link MaskingEngine#isSensitiveKey(String)} for {@code node} itself, not just for
     * its descendants - for a caller whose root node is one property's value rather than a
     * whole subtree, e.g. a {@code @ConfigurationProperties} bean's {@code clientSecret}
     * entry, where the sensitive key names the root, not a nested field.
     */
    public Object mask(String key, Object node) {
        return maskNode(key, node);
    }

    /** Bypasses masking entirely when {@code unmask} is true (see {@link MaskingEngine#mask(String, String, boolean)}). */
    public Object mask(String key, Object node, boolean unmask) {
        return unmask ? node : mask(key, node);
    }

    private Object maskNode(String path, Object value) {
        if (path != null && maskingEngine.isSensitiveKey(path)) {
            return value == null ? null : MaskingRules.MASK;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                String childPath = path == null ? childKey : path + "." + childKey;
                result.put(childKey, maskNode(childPath, entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add(maskNode(path, element));
            }
            return result;
        }
        if (value instanceof String s) {
            return maskingEngine.maskValue(s);
        }
        return value;
    }
}
