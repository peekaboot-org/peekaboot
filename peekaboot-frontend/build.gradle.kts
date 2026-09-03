plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Frontend - Embedded web UI resources"

// No sources and no build step: plain ES modules and CSS under src/main/resources,
// copied into the jar as-is. The convention plugin's gates all no-op on the empty
// Java source set.
