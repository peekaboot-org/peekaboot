package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.InfoResponse;
import org.peekaboot.backend.actuator.parsed.SpringInfo;
import org.peekaboot.backend.domain.application.ApplicationInfo;
import org.peekaboot.backend.masking.MaskingEngine;

class ApplicationMapperTest {

    private final ApplicationMapper mapper = new ApplicationMapper(new MaskingEngine());

    @Test
    void map_shouldExtractBuildInfo() {
        InfoResponse info = new InfoResponse(null, Map.of("artifact", "my-app", "version", "1.0.0"), null, null, null);
        ApplicationInfo result = mapper.map(info, null, false);
        assertThat(result.build()).containsEntry("artifact", "my-app");
        assertThat(result.build()).containsEntry("version", "1.0.0");
    }

    /** {@code info.build} is a free-form map the consuming app fills itself, so its values are masked like any other. */
    @Test
    void map_shouldMaskASensitiveKeyInBuildInfo() {
        InfoResponse info = new InfoResponse(null, Map.of("artifact", "my-app", "apiKey", "hunter2"), null, null, null);

        ApplicationInfo result = mapper.map(info, null, false);

        assertThat(result.build()).containsEntry("artifact", "my-app").containsEntry("apiKey", "******");
    }

    @Test
    void map_shouldReturnBuildInfoVerbatimWhenUnmaskIsTrue() {
        InfoResponse info = new InfoResponse(null, Map.of("apiKey", "hunter2"), null, null, null);

        assertThat(mapper.map(info, null, true).build()).containsEntry("apiKey", "hunter2");
    }

    @Test
    void map_shouldExtractGitInfo() {
        InfoResponse info = new InfoResponse(
                new InfoResponse.GitInfo("main", new InfoResponse.GitInfo.CommitInfo("abc123", "2024-01-01T10:00:00Z")),
                null,
                null,
                null,
                null);
        ApplicationInfo result = mapper.map(info, null, false);
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
                new InfoResponse.JavaInfo(new InfoResponse.JavaInfo.VendorInfo("Eclipse Adoptium", "21.0.1"), "21.0.1"),
                null,
                null);
        ApplicationInfo result = mapper.map(info, null, false);
        assertThat(result.javaVersion()).isEqualTo("21.0.1");
        assertThat(result.javaVendor()).isEqualTo("Eclipse Adoptium");
    }

    @Test
    void map_shouldExtractSpringVersions() {
        SpringInfo spring = new SpringInfo("3.2.0", "6.1.2");
        ApplicationInfo result = mapper.map(null, spring, false);
        assertThat(result.springBootVersion()).isEqualTo("3.2.0");
        assertThat(result.springFrameworkVersion()).isEqualTo("6.1.2");
    }

    @Test
    void map_shouldHandleNullInputs() {
        ApplicationInfo result = mapper.map(null, null, false);
        assertThat(result.build()).isEmpty();
        assertThat(result.git()).isEmpty();
        assertThat(result.javaVersion()).isNull();
        assertThat(result.springBootVersion()).isNull();
    }
}
