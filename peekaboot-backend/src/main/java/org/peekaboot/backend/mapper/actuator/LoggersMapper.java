package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.LoggersResponse;
import org.peekaboot.backend.domain.loggers.LoggerGroup;
import org.peekaboot.backend.domain.loggers.LoggerInfo;
import org.peekaboot.backend.domain.loggers.LoggersInfo;

public class LoggersMapper {

    public LoggersInfo map(LoggersResponse loggersData) {
        if (loggersData == null || loggersData.loggers() == null) {
            return new LoggersInfo(List.of(), 0, 0);
        }

        Map<String, List<LoggerInfo>> byPackage = new LinkedHashMap<>();
        int totalCount = 0;
        int configuredCount = 0;

        for (Map.Entry<String, LoggersResponse.LoggerInfo> entry :
                loggersData.loggers().entrySet()) {
            String name = entry.getKey();
            LoggersResponse.LoggerInfo loggerData = entry.getValue();

            LoggerInfo loggerInfo = new LoggerInfo(name, loggerData.configuredLevel(), loggerData.effectiveLevel());
            String packageName = extractPackageName(name);
            byPackage.computeIfAbsent(packageName, k -> new ArrayList<>()).add(loggerInfo);

            totalCount++;
            if (loggerInfo.isConfigured()) {
                configuredCount++;
            }
        }

        List<LoggerGroup> groups = byPackage.entrySet().stream()
                .map(e -> new LoggerGroup(e.getKey(), e.getValue()))
                .toList();

        return new LoggersInfo(groups, totalCount, configuredCount);
    }

    private String extractPackageName(String loggerName) {
        String[] parts = loggerName.split("\\.", -1);
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return parts[0];
    }
}
