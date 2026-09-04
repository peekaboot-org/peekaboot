plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Backend - Spring Boot controllers, services, and lifecycle listeners"

dependencies {
    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-actuator")
    api("tools.jackson.core:jackson-databind")
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

    // The toolbar shell inlines stylesheets that ship in peekaboot-frontend, read off
    // the runtime classpath. Test scope only: a compile-scope edge would invert the
    // layering, in which frontend is a passive resource bundle nothing depends on.
    testImplementation(project(":peekaboot-frontend"))
    // shared test support (LogCapture)
    testImplementation(project(":peekaboot-test-support"))
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

// The configuration processor turns the @ConfigurationProperties Javadoc into the metadata
// an IDE offers on peekaboot.* keys. Nothing else notices when it stops running.
// Mirrors the Maven enforcer rule on this module.
val checkConfigurationMetadata = tasks.register("checkConfigurationMetadata") {
    description = "Fails if spring-boot-configuration-processor produced no metadata."
    group = "verification"
    dependsOn(tasks.compileJava)
    val metadata = tasks.compileJava.map {
        it.destinationDirectory.file("META-INF/spring-configuration-metadata.json").get().asFile
    }
    doLast {
        check(metadata.get().isFile) { "The configuration processor produced no metadata at ${metadata.get()}." }
    }
}

tasks.named("check") { dependsOn(checkConfigurationMetadata) }
