plugins {
    id("peekaboot.java-conventions")
    `java-test-fixtures`
}

description = "Peekaboot Backend - Spring Boot controllers, services, and lifecycle listeners"

// Test support (LogCapture) for the other modules' tests. Maven packages the same package
// as this module's -tests jar, so it stays in src/test/java: the fixtures source set takes
// the testsupport package from there and the test source set leaves it alone, which keeps
// one location for both builds and one copy of every class on the test classpath.
val testSupportPackage = "org/peekaboot/backend/testsupport/**"
sourceSets {
    testFixtures {
        java.setSrcDirs(listOf("src/test/java"))
        java.include(testSupportPackage)
    }
    test {
        java.exclude(testSupportPackage)
    }
}

dependencies {
    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-actuator")
    api("tools.jackson.core:jackson-databind")
    api("com.github.ben-manes.caffeine:caffeine")
    api("io.micrometer:micrometer-core")
    api("io.micrometer:micrometer-tracing")
    api("net.osslabz:jdbc-url-parser:0.1.1")
    api("com.cronutils:cron-utils:9.2.1")

    // Maven <optional> deps: the host app's own starters provide these at runtime,
    // auto-configuration conditions guard their use.
    compileOnly("org.springframework.boot:spring-boot-web-server")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly("com.zaxxer:HikariCP")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("io.opentelemetry:opentelemetry-sdk-trace")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // The toolbar shell inlines stylesheets that ship in peekaboot-frontend, read off
    // the runtime classpath. Test scope only: a compile-scope edge would invert the
    // layering, in which frontend is a passive resource bundle nothing depends on.
    // LogCapture's API is logback's (Level, ListAppender)
    testFixturesApi("ch.qos.logback:logback-classic")

    testImplementation(project(":peekaboot-frontend"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // otherwise resolves only transitively through spring-boot-starter-test, so a
    // starter change could remove it silently
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf")
}
