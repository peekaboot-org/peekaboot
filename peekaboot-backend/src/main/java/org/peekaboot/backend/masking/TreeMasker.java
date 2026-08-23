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
 */
public final class TreeMasker {

    private final MaskingEngine maskingEngine;

    public TreeMasker(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
    }

    public Object mask(Object node) {
        return maskNode(null, node);
    }

    /**
     * Same as {@link #mask(Object)}, except when {@code unmask} is true, in which case
     * masking is bypassed entirely and {@code node} is returned unchanged. See
     * {@link MaskingEngine#mask(String, String, boolean)} for why this shape.
     */
    public Object mask(Object node, boolean unmask) {
        return unmask ? node : mask(node);
    }

    private Object maskNode(String key, Object value) {
        if (key != null && maskingEngine.isSensitiveKey(key)) {
            return value == null ? null : maskingEngine.mask(key, "x");
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                result.put(childKey, maskNode(childKey, entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add(maskNode(null, element));
            }
            return result;
        }
        if (value instanceof String s) {
            return maskingEngine.maskValue(s);
        }
        return value;
    }
}
