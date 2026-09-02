package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.actuator.parsed.InfoResponse;
import org.peekaboot.backend.domain.runtime.MachineInfo;
import org.peekaboot.backend.domain.runtime.MemoryInfo;
import org.peekaboot.backend.domain.runtime.OsInfo;
import org.peekaboot.backend.domain.runtime.ProcessInfo;
import org.peekaboot.backend.domain.runtime.RuntimeInfo;
import org.peekaboot.backend.domain.runtime.StorageInfo;

public class RuntimeMapper {

    public RuntimeInfo map(InfoResponse info, HealthResponse health) {
        OsInfo osInfo = extractOsInfo(info);
        MemoryInfo memoryInfo = extractMemoryInfo(info);
        List<StorageInfo> storageInfo = extractStorageInfo(health);

        return new RuntimeInfo(osInfo, memoryInfo, storageInfo, ProcessInfo.current(), MachineInfo.current());
    }

    private OsInfo extractOsInfo(InfoResponse info) {
        if (info == null || info.os() == null) {
            return null;
        }

        InfoResponse.OsInfo os = info.os();
        if (os.name() == null && os.version() == null && os.arch() == null) {
            return null;
        }
        return new OsInfo(os.name(), os.version(), os.arch());
    }

    private MemoryInfo extractMemoryInfo(InfoResponse info) {
        if (info == null || info.process() == null || info.process().memory() == null) {
            return null;
        }

        InfoResponse.ProcessInfo.MemoryInfo memory = info.process().memory();

        long heapUsed = memory.heap() != null ? zeroIfNull(memory.heap().used()) : 0;
        long heapMax = memory.heap() != null ? zeroIfNull(memory.heap().max()) : 0;
        long nonHeapUsed =
                memory.nonHeap() != null ? zeroIfNull(memory.nonHeap().used()) : 0;

        if (heapUsed == 0 && heapMax == 0) {
            return null;
        }
        return MemoryInfo.of(heapUsed, heapMax, nonHeapUsed);
    }

    private static long zeroIfNull(Long value) {
        return value != null ? value : 0;
    }

    private List<StorageInfo> extractStorageInfo(HealthResponse health) {
        if (health == null || health.components() == null) {
            return List.of();
        }

        List<StorageInfo> result = new ArrayList<>();
        HealthResponse.HealthComponent diskSpace = health.components().get("diskSpace");

        if (diskSpace != null && diskSpace.details() != null) {
            Map<String, Object> details = diskSpace.details();
            String path = details.get("path") != null ? details.get("path").toString() : "/";
            long total = getLongValue(details, "total");
            long free = getLongValue(details, "free");
            if (total > 0) {
                result.add(StorageInfo.of(path, total, free));
            }
        }
        return result;
    }

    private long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }
}
