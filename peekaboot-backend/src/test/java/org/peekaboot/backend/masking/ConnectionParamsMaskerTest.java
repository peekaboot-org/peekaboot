package org.peekaboot.backend.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectionParamsMaskerTest {

    private final ConnectionParamsMasker masker = new ConnectionParamsMasker(new MaskingEngine());

    @Test
    void maskReplacesSensitiveValuesAndKeepsTheRestInOrder() {
        Map<String, JdbcProperty> params = new LinkedHashMap<>();
        params.put("ssl", new JdbcProperty(PropertySource.QUERY, "true"));
        params.put("password", new JdbcProperty(PropertySource.QUERY, "s3cret"));
        params.put("user", new JdbcProperty(PropertySource.QUERY, "admin"));

        Map<String, String> masked = masker.mask(params);

        assertThat(masked)
                .containsExactly(Map.entry("ssl", "true"), Map.entry("password", "******"), Map.entry("user", "admin"));
    }

    // Run-together names, look-alike spellings and control characters the key-name rules let through, as JDBC
    // drivers spell them, plus credential-style names. keyStoreLocation and keyVaultProviderClientId are not secrets
    // and mask anyway, a cost accepted for a rule no spelling slips past.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "sslpassword",
                "password1",
                "PWD",
                "AUTHZPWD",
                "crypt_key",
                "privatekey",
                "accesstoken",
                "keystoresecret",
                "keyStoreLocation",
                "keyStorePrincipalId",
                "keyVaultProviderClientId",
                "keyVaultProviderClientKey",
                "sslkey",
                "key",
                "pa\u017F\u017Fword",
                "PA\u017F\u017FWORD",
                "trustStorePa\u017F\u017Fword",
                "clientKeyPa\u017F\u017Fword",
                "keyStore\u017Fecret",
                "key\u017Ftore\u017Fecret",
                "AADSecurePrincipal\u017Fecret",
                "aadSecurePrincipal\u017Fecret",
                "pass\u0000word",
                "passw\u0000ord",
                "\u0001password",
                "\tpassword",
                "\npassword",
                "se\u200Bcret",
                "credential",
                "CREDENTIALS",
                "clientCredential"
            })
    void maskHidesTheValueOfEveryKeyThatHoldsASecretWord(String key) {
        Map<String, JdbcProperty> params = Map.of(key, new JdbcProperty(PropertySource.QUERY, "S3Cx"));

        assertThat(masker.mask(params)).containsExactly(Map.entry(key, "******"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sslmode", "connectTimeout", "databaseName", "encrypt", "applicationName"})
    void maskKeepsAHarmlessParamReadable(String key) {
        Map<String, JdbcProperty> params = Map.of(key, new JdbcProperty(PropertySource.QUERY, "require"));

        assertThat(masker.mask(params)).containsExactly(Map.entry(key, "require"));
    }

    @Test
    void maskReturnsAValueVerbatimWhenUnmaskedThoughOnlyTheSecretWordRuleWouldMaskIt() {
        Map<String, JdbcProperty> params = Map.of("sslpassword", new JdbcProperty(PropertySource.QUERY, "S3Cx"));

        assertThat(masker.mask(params, true)).containsExactly(Map.entry("sslpassword", "S3Cx"));
    }

    @Test
    void maskReturnsEveryValueVerbatimWhenUnmasked() {
        Map<String, JdbcProperty> params = Map.of("password", new JdbcProperty(PropertySource.QUERY, "s3cret"));

        assertThat(masker.mask(params, true)).containsEntry("password", "s3cret");
    }

    @Test
    void maskToleratesAbsentParamsAndAbsentValues() {
        Map<String, JdbcProperty> params = new LinkedHashMap<>();
        params.put("password", null);

        assertThat(masker.mask(null)).isEmpty();
        assertThat(masker.mask(params)).containsEntry("password", null);
    }

    @Test
    void maskJudgesANullKeyByItsValue() {
        Map<String, JdbcProperty> params = new LinkedHashMap<>();
        params.put(null, new JdbcProperty(PropertySource.QUERY, "require"));

        assertThat(masker.mask(params)).containsEntry(null, "require");
    }
}
