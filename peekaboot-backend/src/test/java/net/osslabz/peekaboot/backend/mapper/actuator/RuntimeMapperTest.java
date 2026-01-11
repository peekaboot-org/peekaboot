package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.actuator.raw.HealthResponse;
import net.osslabz.peekaboot.backend.actuator.raw.InfoResponse;
import net.osslabz.peekaboot.backend.domain.runtime.RuntimeInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMapperTest {

    private final RuntimeMapper mapper = new RuntimeMapper();

    @Test
    void map_shouldExtractOsInfo() {
        InfoResponse info = new InfoResponse(
            null, null, null,
            new InfoResponse.OsInfo("amd64", "Linux", "5.15"),
            null
        );
        RuntimeInfo result = mapper.map(info, null);
        assertThat(result.os().name()).isEqualTo("Linux");
        assertThat(result.os().version()).isEqualTo("5.15");
        assertThat(result.os().arch()).isEqualTo("amd64");
    }

    @Test
    void map_shouldExtractDiskSpaceFromHealth() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("diskSpace", new HealthResponse.HealthComponent(
                    "UP",
                    Map.of("total", 500_000_000_000L, "free", 200_000_000_000L, "path", "/")
                )),
                List.of()
            ),
            200
        );
        RuntimeInfo result = mapper.map(null, health);
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
        InfoResponse info = new InfoResponse(
            null, null, null, null,
            new InfoResponse.ProcessInfo(
                4,
                new InfoResponse.ProcessInfo.MemoryInfo(
                    new InfoResponse.ProcessInfo.MemoryInfo.HeapInfo(200_000_000L, 100_000_000L, 500_000_000L, 100_000_000L),
                    new InfoResponse.ProcessInfo.MemoryInfo.HeapInfo(60_000_000L, 50_000_000L, -1L, 50_000_000L)
                ),
                "user", 1L, 12345L
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
        InfoResponse info = new InfoResponse(null, null, null, null, null);
        RuntimeInfo result = mapper.map(info, null);
        assertThat(result.os()).isNull();
    }

    @Test
    void map_shouldUseFallbackPathForDiskSpace() {
        HealthResponse health = new HealthResponse(
            new HealthResponse.HealthBody(
                "UP",
                Map.of("diskSpace", new HealthResponse.HealthComponent(
                    "UP",
                    Map.of("total", 1000L, "free", 500L)
                )),
                List.of()
            ),
            200
        );
        RuntimeInfo result = mapper.map(null, health);
        assertThat(result.storage()).hasSize(1);
        assertThat(result.storage().get(0).path()).isEqualTo("/");
    }
}
