package org.peekaboot.backend.masking;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TagMaskerTest {

    private final TagMasker tagMasker = new TagMasker(new MaskingEngine());

    @Test
    void mask_shouldReplaceValueForASensitiveKey() {
        Map<String, String> tags = Map.of("http.request.header.authorization", "Bearer abc123");

        Map<String, String> masked = tagMasker.mask(tags);

        assertThat(masked).containsEntry("http.request.header.authorization", "******");
    }

    @Test
    void mask_shouldLeaveOrdinaryTagsUntouched() {
        Map<String, String> tags = Map.of("http.method", "GET");

        Map<String, String> masked = tagMasker.mask(tags);

        assertThat(masked).containsEntry("http.method", "GET");
    }

    @Test
    void mask_shouldApplyValuePatternRulesToNonSensitiveKeys() {
        Map<String, String> tags = Map.of("http.url", "https://admin:hunter2@example.com/api");

        Map<String, String> masked = tagMasker.mask(tags);

        assertThat(masked.get("http.url")).isEqualTo("https://******@example.com/api");
    }

    @Test
    void mask_shouldPreserveInsertionOrder() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("b", "2");
        tags.put("a", "1");

        Map<String, String> masked = tagMasker.mask(tags);

        assertThat(masked.keySet()).containsExactly("b", "a");
    }

    @Test
    void mask_shouldReturnEmptyMapUnchanged() {
        assertThat(tagMasker.mask(Map.of())).isEmpty();
    }

    @Test
    void mask_shouldReturnNullUnchanged() {
        assertThat(tagMasker.mask(null)).isNull();
    }
}
