package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.application.ApplicationInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationMapperTest {

    private final ApplicationMapper mapper = new ApplicationMapper();

    @Test
    void map_shouldExtractBuildInfo() {
        Map<String, Object> info = Map.of("build", Map.of("version", "1.0.0", "artifact", "my-app"));
        ApplicationInfo result = mapper.map(info, null);
        assertThat(result.build()).containsEntry("version", "1.0.0");
        assertThat(result.build()).containsEntry("artifact", "my-app");
    }

    @Test
    void map_shouldExtractGitInfo() {
        Map<String, Object> info = Map.of("git", Map.of("branch", "main", "commit", Map.of("id", "abc123")));
        ApplicationInfo result = mapper.map(info, null);
        assertThat(result.git()).containsEntry("branch", "main");
    }

    @Test
    void map_shouldExtractJavaInfo() {
        Map<String, Object> info = Map.of(
            "java", Map.of(
                "version", "21.0.1",
                "vendor", Map.of("name", "Eclipse Adoptium")
            )
        );
        ApplicationInfo result = mapper.map(info, null);
        assertThat(result.javaVersion()).isEqualTo("21.0.1");
        assertThat(result.javaVendor()).isEqualTo("Eclipse Adoptium");
    }

    @Test
    void map_shouldExtractSpringVersions() {
        Map<String, Object> spring = Map.of("bootVersion", "3.2.0", "frameworkVersion", "6.1.2");
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
