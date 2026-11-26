package org.peekaboot.testingapp.integration;

import org.springframework.boot.SpringBootVersion;

/**
 * The Spring Boot line the suite runs on, read at runtime: the same test sources run against 4.1 and
 * 4.2, and a test whose subject exists only on 4.2 asks here before asserting it.
 */
final class BootLine {

    private BootLine() {}

    static boolean isAtLeast(int major, int minor) {
        String version = SpringBootVersion.getVersion();
        if (version == null) {
            throw new IllegalStateException("SpringBootVersion found no version in Spring Boot's jar manifest,"
                    + " so the Boot line a test runs on is unknown");
        }
        String[] parts = version.split("[.-]", 3);
        int runningMajor = Integer.parseInt(parts[0]);
        int runningMinor = Integer.parseInt(parts[1]);
        return runningMajor > major || (runningMajor == major && runningMinor >= minor);
    }
}
