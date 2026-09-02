import org.gradle.api.artifacts.component.ModuleComponentIdentifier

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

// The servlet stack and the connection pool are compileOnly in the modules that use them,
// so the host application supplies them and the auto-configuration conditions stay
// meaningful. Handing a consumer one of them would fire those conditions in an application
// that never asked for it. Mirrors the Maven enforcer rule on this module.
val bannedFromConsumers = setOf(
    "jakarta.servlet:jakarta.servlet-api",
    "org.springframework:spring-webmvc",
    "org.springframework.boot:spring-boot-web-server",
    "com.zaxxer:HikariCP",
)

val checkOptionalDependencyContract by tasks.registering {
    description = "Fails if the starter would hand a consumer a servlet stack or a connection pool."
    group = "verification"
    val resolved = configurations.runtimeClasspath.map { conf ->
        conf.incoming.artifacts.artifacts.mapNotNull {
            (it.id.componentIdentifier as? ModuleComponentIdentifier)?.let { m -> "${m.group}:${m.module}" }
        }
    }
    doLast {
        val leaked = resolved.get().filter { it in bannedFromConsumers }
        check(leaked.isEmpty()) { "The starter must not hand a consumer $leaked." }
    }
}

tasks.named("check") { dependsOn(checkOptionalDependencyContract) }
