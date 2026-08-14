package org.peekaboot.backend.actuator.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InfoResponse(
    GitInfo git,
    Map<String, Object> build,
    JavaInfo java,
    OsInfo os,
    ProcessInfo process
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitInfo(
        String branch,
        CommitInfo commit
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record CommitInfo(
            String id,
            String time
        ) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JavaInfo(
        JvmInfo jvm,
        RuntimeInfo runtime,
        VendorInfo vendor,
        String version
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record JvmInfo(String name, String vendor, String version) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record RuntimeInfo(String name, String version) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record VendorInfo(String name, String version) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OsInfo(
        String arch,
        String name,
        String version
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcessInfo(
        Integer cpus,
        MemoryInfo memory,
        String owner,
        Long parentPid,
        Long pid
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record MemoryInfo(
            HeapInfo heap,
            HeapInfo nonHeap
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record HeapInfo(
                Long committed,
                Long init,
                Long max,
                Long used
            ) {
            }
        }
    }
}
