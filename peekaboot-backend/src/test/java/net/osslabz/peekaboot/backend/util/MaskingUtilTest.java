package net.osslabz.peekaboot.backend.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingUtilTest {

    @Test
    void maskHeaders_masksSensitiveHeaders() {
        var headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer secret123",
                "Cookie", "session=abc"
        );

        var masked = MaskingUtil.maskHeaders(headers, false);

        assertThat(masked.get("Content-Type")).isEqualTo("application/json");
        assertThat(masked.get("Authorization")).isEqualTo("********");
        assertThat(masked.get("Cookie")).isEqualTo("********");
    }

    @Test
    void maskHeaders_preservesAllWhenUnmask() {
        var headers = Map.of("Authorization", "Bearer secret123");

        var masked = MaskingUtil.maskHeaders(headers, true);

        assertThat(masked.get("Authorization")).isEqualTo("Bearer secret123");
    }

    @Test
    void maskHeaders_handlesNullInput() {
        var masked = MaskingUtil.maskHeaders(null, false);

        assertThat(masked).isEmpty();
    }

    @Test
    void isSensitiveHeader_detectsSensitiveHeaders() {
        assertThat(MaskingUtil.isSensitiveHeader("Authorization")).isTrue();
        assertThat(MaskingUtil.isSensitiveHeader("AUTHORIZATION")).isTrue();
        assertThat(MaskingUtil.isSensitiveHeader("authorization")).isTrue();
        assertThat(MaskingUtil.isSensitiveHeader("Cookie")).isTrue();
        assertThat(MaskingUtil.isSensitiveHeader("Set-Cookie")).isTrue();
        assertThat(MaskingUtil.isSensitiveHeader("X-Auth-Token")).isTrue();
        assertThat(MaskingUtil.isSensitiveHeader("X-Api-Key")).isTrue();
    }

    @Test
    void isSensitiveHeader_detectsNonSensitiveHeaders() {
        assertThat(MaskingUtil.isSensitiveHeader("Content-Type")).isFalse();
        assertThat(MaskingUtil.isSensitiveHeader("Accept")).isFalse();
        assertThat(MaskingUtil.isSensitiveHeader("X-Request-Id")).isFalse();
    }

    @Test
    void isSensitiveHeader_handlesNullInput() {
        assertThat(MaskingUtil.isSensitiveHeader(null)).isFalse();
    }

    @Test
    void maskJsonFields_returnsNullForNullInput() {
        assertThat(MaskingUtil.maskJsonFields(null, false)).isNull();
    }

    @Test
    void maskJsonFields_returnsOriginalWhenUnmask() {
        String json = "{\"password\": \"secret\"}";
        assertThat(MaskingUtil.maskJsonFields(json, true)).isEqualTo(json);
    }
}
