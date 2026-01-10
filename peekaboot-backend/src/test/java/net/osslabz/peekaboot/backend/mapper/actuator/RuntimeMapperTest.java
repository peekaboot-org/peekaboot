package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.runtime.RuntimeInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMapperTest {

    private final RuntimeMapper mapper = new RuntimeMapper();

    @Test
    void map_shouldExtractOsInfo() {
        Map<String, Object> info = Map.of("os", Map.of("name", "Linux", "version", "5.15", "arch", "amd64"));
        RuntimeInfo result = mapper.map(info, null);
        assertThat(result.os().name()).isEqualTo("Linux");
        assertThat(result.os().version()).isEqualTo("5.15");
        assertThat(result.os().arch()).isEqualTo("amd64");
    }

    @Test
    void map_shouldExtractDiskSpaceFromHealth() {
        Map<String, Object> healthComponents = Map.of(
            "diskSpace", Map.of("status", "UP", "details", Map.of("total", 500_000_000_000L, "free", 200_000_000_000L, "path", "/"))
        );
        RuntimeInfo result = mapper.map(Map.of(), healthComponents);
        assertThat(result.storage()).hasSize(1);
        assertThat(result.storage().get(0).usedPercent()).isEqualTo(60.0);
    }

    @Test
    void map_shouldHandleNullInputs() {
        RuntimeInfo result = mapper.map(null, null);
        assertThat(result.os()).isNull();
        assertThat(result.memory()).isNull();
        assertThat(result.storage()).isEmpty();
    }

    @Test
    void map_shouldExtractMemoryInfo() {
        Map<String, Object> info = Map.of(
            "process", Map.of(
                "memory", Map.of("heap", 100_000_000L, "heapMax", 500_000_000L, "nonHeap", 50_000_000L)
            )
        );
        RuntimeInfo result = mapper.map(info, null);
        assertThat(result.memory()).isNotNull();
        assertThat(result.memory().heapUsed()).isEqualTo(100_000_000L);
        assertThat(result.memory().heapMax()).isEqualTo(500_000_000L);
        assertThat(result.memory().heapUsedPercent()).isEqualTo(20.0);
    }

    @Test
    void map_shouldHandleMissingOsInfo() {
        Map<String, Object> info = Map.of("something", "else");
        RuntimeInfo result = mapper.map(info, null);
        assertThat(result.os()).isNull();
    }

    @Test
    void map_shouldUseFallbackPathForDiskSpace() {
        Map<String, Object> healthComponents = Map.of(
            "diskSpace", Map.of("details", Map.of("total", 1000L, "free", 500L))
        );
        RuntimeInfo result = mapper.map(Map.of(), healthComponents);
        assertThat(result.storage()).hasSize(1);
        assertThat(result.storage().get(0).path()).isEqualTo("/");
    }
}
