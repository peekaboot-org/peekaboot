plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Spring Boot Starter - Dependency aggregator for easy integration"

// No sources - the jar is deliberately empty, exactly like the Maven artifact.
dependencies {
    api(project(":peekaboot-spring-boot-autoconfigure"))
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-opentelemetry")
}
