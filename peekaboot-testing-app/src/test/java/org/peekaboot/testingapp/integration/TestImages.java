package org.peekaboot.testingapp.integration;

/** The container images the ITs start, the same tags compose.yml runs the demo on (ComposeImagesTest). */
final class TestImages {

    static final String MYSQL = "mysql:9";

    static final String REDIS = "redis:8";

    static final String HTTPBIN = "ghcr.io/mccutchen/go-httpbin:2.25.0";

    private TestImages() {}
}
