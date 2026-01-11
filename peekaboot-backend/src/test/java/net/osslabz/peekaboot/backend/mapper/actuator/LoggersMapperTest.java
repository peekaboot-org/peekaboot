package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.actuator.raw.LoggersResponse;
import net.osslabz.peekaboot.backend.domain.loggers.LoggersInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoggersMapperTest {

    private final LoggersMapper mapper = new LoggersMapper();

    @Test
    void map_shouldGroupLoggersByPackage() {
        Map<String, LoggersResponse.LoggerInfo> loggers = new LinkedHashMap<>();
        loggers.put("com.example.service.UserService", new LoggersResponse.LoggerInfo(null, "INFO"));
        loggers.put("com.example.controller.UserController", new LoggersResponse.LoggerInfo(null, "DEBUG"));
        loggers.put("org.springframework.boot.Application", new LoggersResponse.LoggerInfo(null, "WARN"));

        LoggersResponse loggersData = new LoggersResponse(List.of(), loggers, Map.of());
        LoggersInfo result = mapper.map(loggersData);

        assertThat(result.packages()).hasSize(2);
        assertThat(result.totalCount()).isEqualTo(3);
    }

    @Test
    void map_shouldCountConfiguredLoggers() {
        Map<String, LoggersResponse.LoggerInfo> loggers = new LinkedHashMap<>();
        loggers.put("com.example.Foo", new LoggersResponse.LoggerInfo("DEBUG", "INFO"));
        loggers.put("com.example.Bar", new LoggersResponse.LoggerInfo(null, "INFO"));

        LoggersResponse loggersData = new LoggersResponse(List.of(), loggers, Map.of());
        LoggersInfo result = mapper.map(loggersData);

        assertThat(result.configuredCount()).isEqualTo(1);
        assertThat(result.totalCount()).isEqualTo(2);
    }

    @Test
    void map_shouldHandleNullInput() {
        LoggersInfo result = mapper.map(null);
        assertThat(result.packages()).isEmpty();
        assertThat(result.totalCount()).isZero();
        assertThat(result.configuredCount()).isZero();
    }

    @Test
    void map_shouldHandleEmptyLoggers() {
        LoggersResponse loggersData = new LoggersResponse(List.of(), Map.of(), Map.of());
        LoggersInfo result = mapper.map(loggersData);
        assertThat(result.packages()).isEmpty();
    }

    @Test
    void map_shouldHandleSinglePartPackageName() {
        Map<String, LoggersResponse.LoggerInfo> loggers = Map.of(
            "ROOT", new LoggersResponse.LoggerInfo(null, "INFO")
        );
        LoggersResponse loggersData = new LoggersResponse(List.of(), loggers, Map.of());
        LoggersInfo result = mapper.map(loggersData);

        assertThat(result.packages()).hasSize(1);
        assertThat(result.packages().get(0).packageName()).isEqualTo("ROOT");
    }
}
