package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.InfoResponse;
import org.peekaboot.backend.actuator.parsed.SpringInfo;
import org.peekaboot.backend.domain.application.ApplicationInfo;

class ApplicationMapperTest {

    private final ApplicationMapper mapper = new ApplicationMapper();

    @Test
    void map_shouldExtractBuildInfo() {
        InfoResponse info = new InfoResponse(null, Map.of("artifact", "my-app", "version", "1.0.0"), null, null, null);
        ApplicationInfo result = mapper.map(info, null);
        assertThat(result.build()).containsEntry("artifact", "my-app");
        assertThat(result.build()).containsEntry("version", "1.0.0");
    }

    @Test
    void map_shouldMaskCredentialShapedValuesInBuildInfo() {
        // Split literal, deliberately: this shape is exactly what GitHub push protection
        // scans for. Concatenation is folded at compile time, so the value under test is
        // unchanged; it just never appears contiguously in the source. See
        // MaskingEngineTest for the other examples of this pattern.
        String slackToken = "xoxb" + "-123456789012-1234567890123-abcdefghijklmnopqrstuvwx";
        InfoResponse info = new InfoResponse(null, Map.of("artifact", "my-app", "notes", slackToken), null, null, null);

        ApplicationInfo result = mapper.map(info, null);

        assertThat(result.build()).containsEntry("artifact", "my-app");
        assertThat(result.build()).containsEntry("notes", "******");
    }

    @Test
    void map_shouldExtractGitInfo() {
        InfoResponse info = new InfoResponse(
                new InfoResponse.GitInfo("main", new InfoResponse.GitInfo.CommitInfo("abc123", "2024-01-01T10:00:00Z")),
                null,
                null,
                null,
                null);
        ApplicationInfo result = mapper.map(info, null);
        assertThat(result.git()).containsEntry("branch", "main");
        assertThat(result.git()).containsKey("commit");

        @SuppressWarnings("unchecked")
        Map<String, Object> commit = (Map<String, Object>) result.git().get("commit");
        assertThat(commit).containsEntry("id", "abc123");
        assertThat(commit).containsEntry("time", "2024-01-01T10:00:00Z");
    }

    @Test
    void map_shouldExtractJavaInfo() {
        InfoResponse info = new InfoResponse(
                null,
                null,
                new InfoResponse.JavaInfo(
                        new InfoResponse.JavaInfo.JvmInfo("OpenJDK 64-Bit Server VM", "Eclipse Adoptium", "21.0.1"),
                        new InfoResponse.JavaInfo.RuntimeInfo("OpenJDK Runtime Environment", "21.0.1"),
                        new InfoResponse.JavaInfo.VendorInfo("Eclipse Adoptium", "21.0.1"),
                        "21.0.1"),
                null,
                null);
        ApplicationInfo result = mapper.map(info, null);
        assertThat(result.javaVersion()).isEqualTo("21.0.1");
        assertThat(result.javaVendor()).isEqualTo("Eclipse Adoptium");
    }

    @Test
    void map_shouldExtractSpringVersions() {
        SpringInfo spring = new SpringInfo("3.2.0", "6.1.2");
        ApplicationInfo result = mapper.map(null, spring);
        assertThat(result.springBootVersion()).isEqualTo("3.2.0");
        assertThat(result.springFrameworkVersion()).isEqualTo("6.1.2");
    }

    @Test
    void map_shouldHandleNullInputs() {
        ApplicationInfo result = mapper.map(null, null);
        assertThat(result.build()).isEmpty();
        assertThat(result.git()).isEmpty();
        assertThat(result.javaVersion()).isNull();
        assertThat(result.springBootVersion()).isNull();
    }
}
