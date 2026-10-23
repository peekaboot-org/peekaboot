package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

/** An absent {@code build} section binds as empty (see {@link ActuatorResponseParser}). */
public record InfoResponse(
        GitDescriptor git, Map<String, Object> build, JavaDescriptor java, OsDescriptor os, ProcessDescriptor process) {

    public InfoResponse {
        build = Absent.orEmpty(build);
    }

    public record GitDescriptor(String branch, CommitDescriptor commit) {
        /** {@code id} is a String, or {@code {full, abbrev}} under {@code management.info.git.mode=full}. */
        public record CommitDescriptor(Object id, String time) {}
    }

    public record JavaDescriptor(VendorDescriptor vendor, String version) {
        public record VendorDescriptor(String name) {}
    }

    public record OsDescriptor(String arch, String name, String version) {}

    public record ProcessDescriptor(MemoryDescriptor memory) {
        public record MemoryDescriptor(MemoryUsageDescriptor heap, MemoryUsageDescriptor nonHeap) {
            public record MemoryUsageDescriptor(Long max, Long used) {}
        }
    }
}
