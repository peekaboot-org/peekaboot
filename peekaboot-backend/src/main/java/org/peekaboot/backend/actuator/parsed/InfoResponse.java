package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

public record InfoResponse(GitInfo git, Map<String, Object> build, JavaInfo java, OsInfo os, ProcessInfo process) {

    public record GitInfo(String branch, CommitInfo commit) {
        public record CommitInfo(String id, String time) {}
    }

    public record JavaInfo(VendorInfo vendor, String version) {
        public record VendorInfo(String name, String version) {}
    }

    public record OsInfo(String arch, String name, String version) {}

    public record ProcessInfo(MemoryInfo memory) {
        public record MemoryInfo(HeapInfo heap, HeapInfo nonHeap) {
            public record HeapInfo(Long max, Long used) {}
        }
    }
}
