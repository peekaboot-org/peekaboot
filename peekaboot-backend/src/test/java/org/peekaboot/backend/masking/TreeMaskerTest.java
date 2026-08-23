package org.peekaboot.backend.masking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TreeMaskerTest {

    private final TreeMasker treeMasker = new TreeMasker(new MaskingEngine());

    @Test
    @SuppressWarnings("unchecked")
    void mask_shouldReplaceWholeValueForATopLevelSensitiveKey() {
        Object masked = treeMasker.mask(Map.of("apiKey", "AKIAABCDEFGHIJKLMNOP"));

        assertThat((Map<String, Object>) masked).containsEntry("apiKey", "******");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_shouldReplaceANestedObjectEntirelyWhenItsKeyIsSensitive() {
        Map<String, Object> tree = Map.of(
            "connectionParams", Map.of(
                "password", Map.of("value", "hunter2", "source", "QUERY"),
                "mode", Map.of("value", "MEMORY", "source", "DERIVED")
            )
        );

        Object masked = treeMasker.mask(tree);

        Map<String, Object> connectionParams = (Map<String, Object>) ((Map<String, Object>) masked).get("connectionParams");
        assertThat(connectionParams.get("password")).isEqualTo("******");
        assertThat(connectionParams.get("mode")).isEqualTo(Map.of("value", "MEMORY", "source", "DERIVED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_shouldApplyValuePatternRulesToStringLeavesUnderInnocuousKeys() {
        Object masked = treeMasker.mask(Map.of("url", "jdbc:postgresql://admin:hunter2@localhost/db"));

        assertThat(((Map<String, Object>) masked).get("url")).isEqualTo("jdbc:postgresql://******@localhost/db");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_shouldRecurseIntoLists() {
        Object masked = treeMasker.mask(Map.of("items", List.of(
            Map.of("password", "hunter2"),
            Map.of("name", "ok")
        )));

        List<Object> items = (List<Object>) ((Map<String, Object>) masked).get("items");
        assertThat((Map<String, Object>) items.get(0)).containsEntry("password", "******");
        assertThat((Map<String, Object>) items.get(1)).containsEntry("name", "ok");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_shouldLeaveNonStringScalarsUntouched() {
        Object masked = treeMasker.mask(Map.of("port", 8080, "enabled", true));

        assertThat((Map<String, Object>) masked).containsEntry("port", 8080).containsEntry("enabled", true);
    }

    @Test
    void mask_shouldReturnNullForNullNode() {
        assertThat(treeMasker.mask(null)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_shouldReturnTreeUnchangedWhenUnmaskIsTrue() {
        Object masked = treeMasker.mask(Map.of("apiKey", "AKIAABCDEFGHIJKLMNOP"), true);

        assertThat((Map<String, Object>) masked).containsEntry("apiKey", "AKIAABCDEFGHIJKLMNOP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_shouldBehaveLikeTheOneArgOverloadWhenUnmaskIsFalse() {
        Object masked = treeMasker.mask(Map.of("apiKey", "AKIAABCDEFGHIJKLMNOP"), false);

        assertThat((Map<String, Object>) masked).containsEntry("apiKey", "******");
    }

    // Known Defect C1: ConfigMapper's caller has a root node that IS one property's value,
    // not a subtree it needs checked for descendants only - the sensitive key can name the
    // root itself (a @ConfigurationProperties bean's "client-secret" entry). The one-arg
    // mask(Object) overload checks descendants but never the root, so this key-aware
    // overload exists to also check the root against isSensitiveKey.
    @Test
    void mask_withKey_shouldReplaceTheWholeValueWhenTheRootKeyItselfIsSensitive() {
        Object masked = treeMasker.mask("client-secret", "GOCSPX-SuperSecretValue");

        assertThat(masked).isEqualTo("******");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mask_withKey_shouldStillRecurseIntoDescendantsWhenTheRootKeyIsInnocuous() {
        Map<String, Object> google = Map.of("clientId", "abc123", "clientSecret", "GOCSPX-SuperSecretValue");

        Object masked = treeMasker.mask("registration", Map.of("google", google));

        Map<String, Object> registration = (Map<String, Object>) masked;
        Map<String, Object> maskedGoogle = (Map<String, Object>) registration.get("google");
        assertThat(maskedGoogle).containsEntry("clientId", "abc123").containsEntry("clientSecret", "******");
    }

    @Test
    void mask_withKeyAndUnmask_shouldReturnTheNodeVerbatimWhenUnmaskIsTrueEvenForASensitiveRootKey() {
        Object masked = treeMasker.mask("client-secret", "GOCSPX-SuperSecretValue", true);

        assertThat(masked).isEqualTo("GOCSPX-SuperSecretValue");
    }
}
