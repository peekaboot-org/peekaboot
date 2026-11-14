import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Spring Boot Starter - Dependency aggregator for easy integration"

val datasourceMicrometerVersion = pomProperty("datasource-micrometer.version")

// No sources - the jar is deliberately empty, exactly like the Maven artifact.
dependencies {
    api(project(":peekaboot-spring-boot-autoconfigure"))
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-opentelemetry")
    // Query capture - see the pom for why both, and what a host that instruments its own
    // DataSource does instead.
    api("net.ttddyy.observation:datasource-micrometer-spring-boot:$datasourceMicrometerVersion")
    api("net.ttddyy.observation:datasource-micrometer-opentelemetry:$datasourceMicrometerVersion")
}

// The servlet stack, the web server, the connection pool, the ORM and the migration tools are
// compileOnly in the modules that use them, so the host application supplies them and the
// auto-configuration conditions stay meaningful. Handing a consumer one of them would fire
// those conditions in an application that never asked for it. Mirrors the Maven enforcer rule
// on this module.
val bannedFromConsumers = setOf(
    "jakarta.servlet:jakarta.servlet-api",
    "org.springframework:spring-webmvc",
    "org.springframework.boot:spring-boot-web-server",
    "com.zaxxer:HikariCP",
    "org.hibernate.orm:hibernate-core",
    "org.flywaydb:flyway-core",
    "org.liquibase:liquibase-core",
    "org.springframework.boot:spring-boot-flyway",
    "org.springframework.boot:spring-boot-liquibase",
    "org.apache.tomcat.embed:tomcat-embed-core",
    "org.springframework.boot:spring-boot-tomcat",
    "org.springframework.boot:spring-boot-webmvc",
)

val checkOptionalDependencyContract = tasks.register("checkOptionalDependencyContract") {
    description = "Fails if the starter would hand a consumer a servlet stack, a web server, " +
        "a connection pool, an ORM or a migration tool."
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
