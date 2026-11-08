package org.peekaboot.backend.actuator.parsed;

import java.util.Map;

/** An absent {@code build} section binds as empty (see {@link ActuatorResponseParser}). */
public record InfoResponse(Map<String, Object> build, JavaDescriptor java, OsDescriptor os, ProcessDescriptor process) {

    public InfoResponse {
        build = Absent.orEmpty(build);
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
