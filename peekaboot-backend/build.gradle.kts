plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Backend - Spring Boot controllers, services, and lifecycle listeners"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

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
    compileOnly("io.opentelemetry:opentelemetry-sdk-trace")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // The toolbar shell inlines stylesheets that ship in peekaboot-frontend, read off
    // the runtime classpath. Test scope only: a compile-scope edge would invert the
    // layering, in which frontend is a passive resource bundle nothing depends on.
    testImplementation(project(":peekaboot-frontend"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf")
}
