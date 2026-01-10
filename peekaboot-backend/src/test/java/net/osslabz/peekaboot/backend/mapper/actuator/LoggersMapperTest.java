package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.loggers.LoggersInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoggersMapperTest {

    private final LoggersMapper mapper = new LoggersMapper();

    @Test
    void map_shouldGroupLoggersByPackage() {
        Map<String, Object> loggers = new LinkedHashMap<>();
        loggers.put("com.example.service.UserService", Map.of("effectiveLevel", "INFO"));
        loggers.put("com.example.controller.UserController", Map.of("effectiveLevel", "DEBUG"));
        loggers.put("org.springframework.boot.Application", Map.of("effectiveLevel", "WARN"));

        Map<String, Object> loggersData = Map.of("loggers", loggers);
        LoggersInfo result = mapper.map(loggersData);

        assertThat(result.packages()).hasSize(2);
        assertThat(result.totalCount()).isEqualTo(3);
    }

    @Test
    void map_shouldCountConfiguredLoggers() {
        Map<String, Object> loggers = new LinkedHashMap<>();
        loggers.put("com.example.Foo", Map.of("effectiveLevel", "INFO", "configuredLevel", "DEBUG"));
        loggers.put("com.example.Bar", Map.of("effectiveLevel", "INFO"));

        Map<String, Object> loggersData = Map.of("loggers", loggers);
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
        Map<String, Object> loggersData = Map.of("loggers", Map.of());
        LoggersInfo result = mapper.map(loggersData);
        assertThat(result.packages()).isEmpty();
    }

    @Test
    void map_shouldHandleSinglePartPackageName() {
        Map<String, Object> loggers = Map.of("ROOT", Map.of("effectiveLevel", "INFO"));
        Map<String, Object> loggersData = Map.of("loggers", loggers);
        LoggersInfo result = mapper.map(loggersData);

        assertThat(result.packages()).hasSize(1);
        assertThat(result.packages().get(0).packageName()).isEqualTo("ROOT");
    }
}
