package org.peekaboot.backend.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.Test;

class ConnectionParamsMaskerTest {

    private final ConnectionParamsMasker masker = new ConnectionParamsMasker(new MaskingEngine());

    @Test
    void mask_replacesSensitiveValuesAndKeepsTheRestInOrder() {
        Map<String, JdbcProperty> params = new LinkedHashMap<>();
        params.put("ssl", new JdbcProperty(PropertySource.QUERY, "true"));
        params.put("password", new JdbcProperty(PropertySource.QUERY, "s3cret"));
        params.put("user", new JdbcProperty(PropertySource.QUERY, "admin"));

        Map<String, String> masked = masker.mask(params);

        assertThat(masked)
                .containsExactly(Map.entry("ssl", "true"), Map.entry("password", "******"), Map.entry("user", "admin"));
    }

    @Test
    void mask_returnsEveryValueVerbatimWhenUnmasked() {
        Map<String, JdbcProperty> params = Map.of("password", new JdbcProperty(PropertySource.QUERY, "s3cret"));

        assertThat(masker.mask(params, true)).containsEntry("password", "s3cret");
    }

    @Test
    void mask_toleratesAbsentParamsAndAbsentValues() {
        Map<String, JdbcProperty> params = new LinkedHashMap<>();
        params.put("password", null);

        assertThat(masker.mask(null)).isEmpty();
        assertThat(masker.mask(params)).containsEntry("password", null);
    }
}
