package org.peekaboot.backend.actuator.parsed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InfoResponse(GitInfo git, Map<String, Object> build, JavaInfo java, OsInfo os, ProcessInfo process) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitInfo(String branch, CommitInfo commit) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record CommitInfo(String id, String time) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JavaInfo(VendorInfo vendor, String version) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record VendorInfo(String name, String version) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OsInfo(String arch, String name, String version) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcessInfo(MemoryInfo memory) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record MemoryInfo(HeapInfo heap, HeapInfo nonHeap) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            public record HeapInfo(Long max, Long used) {}
        }
    }
}
