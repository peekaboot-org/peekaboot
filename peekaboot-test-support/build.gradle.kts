plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Test Support - shared test helpers for the sibling modules' tests, never published"

dependencies {
    // api, not implementation: LogCapture's API is logback's (Level, ListAppender)
    api("org.slf4j:slf4j-api")
    api("ch.qos.logback:logback-classic")
}
