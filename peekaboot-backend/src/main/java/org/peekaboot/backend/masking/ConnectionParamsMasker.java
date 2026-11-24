package org.peekaboot.backend.masking;

import java.util.LinkedHashMap;
import java.util.Map;
import net.osslabz.jdbc.JdbcProperty;

/**
 * Renders a JDBC URL's connection parameters as plain strings with every sensitive value
 * masked - the one form both the datasource API response and the startup banner may
 * carry, so a {@code password=} URL parameter is caught the same way on either path.
 *
 * <p>A param's name is judged by {@link MaskingEngine#isSensitiveConnectionParam(String)},
 * which on top of the key-name rules masks a name holding a secret word anywhere: drivers
 * run words together ("sslpassword", "keyVaultProviderClientKey").
 */
public final class ConnectionParamsMasker {

    private final MaskingEngine maskingEngine;

    public ConnectionParamsMasker(MaskingEngine maskingEngine) {
        this.maskingEngine = maskingEngine;
    }

    public Map<String, String> mask(Map<String, JdbcProperty> properties) {
        return mask(properties, false);
    }

    /** Bypasses masking entirely when {@code unmask} is true (see {@link MaskingEngine#maskProperty(String, String, boolean)}). */
    public Map<String, String> mask(Map<String, JdbcProperty> properties, boolean unmask) {
        if (properties == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JdbcProperty> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue().value() : null;
            result.put(key, maskedValue(key, value, unmask));
        }
        return result;
    }

    private String maskedValue(String key, String value, boolean unmask) {
        if (unmask || value == null) {
            return value;
        }
        return maskingEngine.isSensitiveConnectionParam(key)
                ? MaskingEngine.MASK_LITERAL
                : maskingEngine.maskValue(value);
    }
}
