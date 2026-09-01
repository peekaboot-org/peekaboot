package org.peekaboot.backend.masking;

import java.util.LinkedHashMap;
import java.util.Map;
import net.osslabz.jdbc.JdbcProperty;

/**
 * Renders a JDBC URL's connection parameters as plain strings with every sensitive value
 * masked - the one form both the datasource API response and the startup banner may
 * carry, so a {@code password=} URL parameter is caught the same way on either path.
 */
public final class ConnectionParamsMasker {

    private final MaskingEngine maskingEngine = new MaskingEngine();

    public Map<String, String> mask(Map<String, JdbcProperty> properties) {
        return mask(properties, false);
    }

    /**
     * Same as {@link #mask(Map)}, except when {@code unmask} is true, in which case every
     * value is returned verbatim. See {@link MaskingEngine#mask(String, String, boolean)}
     * for why this shape.
     */
    public Map<String, String> mask(Map<String, JdbcProperty> properties, boolean unmask) {
        if (properties == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JdbcProperty> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue().value() : null;
            result.put(key, maskingEngine.mask(key, value, unmask));
        }
        return result;
    }
}
