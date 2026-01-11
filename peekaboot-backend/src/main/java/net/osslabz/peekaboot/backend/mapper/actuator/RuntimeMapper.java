package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.actuator.raw.HealthResponse;
import net.osslabz.peekaboot.backend.actuator.raw.InfoResponse;
import net.osslabz.peekaboot.backend.domain.runtime.MemoryInfo;
import net.osslabz.peekaboot.backend.domain.runtime.OsInfo;
import net.osslabz.peekaboot.backend.domain.runtime.RuntimeInfo;
import net.osslabz.peekaboot.backend.domain.runtime.StorageInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class RuntimeMapper {

    public RuntimeInfo map(InfoResponse info, HealthResponse health) {
        OsInfo osInfo = extractOsInfo(info);
        MemoryInfo memoryInfo = extractMemoryInfo(info);
        List<StorageInfo> storageInfo = extractStorageInfo(health);

        return new RuntimeInfo(osInfo, memoryInfo, storageInfo);
    }

    private OsInfo extractOsInfo(InfoResponse info) {
        if (info == null || info.os() == null) return null;

        InfoResponse.OsInfo os = info.os();
        if (os.name() == null && os.version() == null && os.arch() == null) return null;
        return new OsInfo(os.name(), os.version(), os.arch());
    }

    private MemoryInfo extractMemoryInfo(InfoResponse info) {
        if (info == null || info.process() == null || info.process().memory() == null) return null;

        InfoResponse.ProcessInfo.MemoryInfo memory = info.process().memory();

        long heapUsed = 0;
        long heapMax = 0;
        long nonHeapUsed = 0;

        if (memory.heap() != null) {
            heapUsed = memory.heap().used() != null ? memory.heap().used() : 0;
            heapMax = memory.heap().max() != null ? memory.heap().max() : 0;
        }

        if (memory.nonHeap() != null) {
            nonHeapUsed = memory.nonHeap().used() != null ? memory.nonHeap().used() : 0;
        }

        if (heapUsed == 0 && heapMax == 0) return null;
        return MemoryInfo.of(heapUsed, heapMax, nonHeapUsed);
    }

    private List<StorageInfo> extractStorageInfo(HealthResponse health) {
        if (health == null || health.body() == null || health.body().components() == null) {
            return List.of();
        }

        List<StorageInfo> result = new ArrayList<>();
        HealthResponse.HealthComponent diskSpace = health.body().components().get("diskSpace");

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
        if (value instanceof Number n) return n.longValue();
        return 0;
    }
}
