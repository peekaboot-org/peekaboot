package net.osslabz.peekaboot.backend.mapper.actuator;

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

    @SuppressWarnings("unchecked")
    public RuntimeInfo map(Map<String, Object> info, Map<String, Object> healthComponents) {
        OsInfo osInfo = extractOsInfo(info);
        MemoryInfo memoryInfo = extractMemoryInfo(info);
        List<StorageInfo> storageInfo = extractStorageInfo(healthComponents);

        return new RuntimeInfo(osInfo, memoryInfo, storageInfo);
    }

    @SuppressWarnings("unchecked")
    private OsInfo extractOsInfo(Map<String, Object> info) {
        if (info == null) return null;
        Object osObj = info.get("os");
        if (!(osObj instanceof Map<?, ?> os)) return null;

        String name = getStringValue(os, "name");
        String version = getStringValue(os, "version");
        String arch = getStringValue(os, "arch");

        if (name == null && version == null && arch == null) return null;
        return new OsInfo(name, version, arch);
    }

    @SuppressWarnings("unchecked")
    private MemoryInfo extractMemoryInfo(Map<String, Object> info) {
        if (info == null) return null;

        Object processObj = info.get("process");
        if (!(processObj instanceof Map<?, ?> process)) return null;

        Object memoryObj = process.get("memory");
        if (!(memoryObj instanceof Map<?, ?> memory)) return null;

        // heap and nonHeap are nested objects with used/max/etc.
        long heapUsed = 0;
        long heapMax = 0;
        long nonHeapUsed = 0;

        Object heapObj = memory.get("heap");
        if (heapObj instanceof Map<?, ?> heap) {
            heapUsed = getLongValue(heap, "used");
            heapMax = getLongValue(heap, "max");
        }

        Object nonHeapObj = memory.get("nonHeap");
        if (nonHeapObj instanceof Map<?, ?> nonHeap) {
            nonHeapUsed = getLongValue(nonHeap, "used");
        }

        if (heapUsed == 0 && heapMax == 0) return null;
        return MemoryInfo.of(heapUsed, heapMax, nonHeapUsed);
    }

    @SuppressWarnings("unchecked")
    private List<StorageInfo> extractStorageInfo(Map<String, Object> healthComponents) {
        if (healthComponents == null) return List.of();

        List<StorageInfo> result = new ArrayList<>();
        Object diskObj = healthComponents.get("diskSpace");
        if (diskObj instanceof Map<?, ?> disk) {
            Object detailsObj = disk.get("details");
            if (detailsObj instanceof Map<?, ?> details) {
                String path = getStringValue(details, "path");
                long total = getLongValue(details, "total");
                long free = getLongValue(details, "free");
                if (total > 0) {
                    result.add(StorageInfo.of(path != null ? path : "/", total, free));
                }
            }
        }
        return result;
    }

    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private long getLongValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.longValue();
        return 0;
    }
}
