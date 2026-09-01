plugins {
    id("peekaboot.java-conventions")
    id("org.springframework.boot")
    id("com.gorylenko.gradle-git-properties")
}

description = "Peekaboot Testing App - sample application for manual and automated UI testing"

// In Maven this module deliberately parents to spring-boot-starter-parent so it consumes
// the published starter exactly as a real user would. The Gradle build cannot reproduce
// that proof either way (project() substitution is inherent here), so it simply shares
// the conventions; the Maven build remains the consume-as-a-user check.

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("net.ttddyy.observation:datasource-micrometer-spring-boot:2.2.1")
    implementation("net.ttddyy.observation:datasource-micrometer-opentelemetry:2.2.1")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("com.github.gavlyukovskiy:datasource-proxy-spring-boot-starter:2.0.1")
    implementation("org.hibernate.orm:hibernate-micrometer")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation(project(":peekaboot-spring-boot-starter"))

    developmentOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    // test scope only, exactly like the Maven build: proves the SecurityFilterChain the
    // website's security page tells readers to write, without securing the sample app.
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("com.h2database:h2")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("com.microsoft.playwright:playwright:1.62.0")
}

// Maven compiles this module with -parameters (spring-boot-starter-parent default).
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

springBoot {
    // Fixed build.time (same instant as Maven's project.build.outputTimestamp), so the
    // boot jar is byte-identical across rebuilds.
    buildInfo {
        properties {
            time = "2025-04-23T12:21:37Z"
        }
    }
}

// gorylenko writes git.properties into a generated resources dir that the sources jar
// sweeps up; the ordering edge has to be explicit (this module is not published, so the
// extra file in its sources jar is harmless).
tasks.named("sourcesJar") {
    dependsOn(tasks.named("generateGitProperties"))
}

gitProperties {
    failOnNoGitDirectory = false
    // Pinned like build.time above: git-commit-id on the Maven side derives
    // git.build.time from project.build.outputTimestamp for reproducible builds.
    customProperty("git.build.time", "2025-04-23T12:21:37Z")
    keys = listOf(
        "git.branch",
        "git.tags",
        "git.build.time",
        "git.build.version",
        "git.commit.id.abbrev",
        "git.commit.id",
    )
}

// The *IT suite runs concurrent test classes in one JVM; same knobs and defaults as the
// Maven build (peekaboot.it.threads in gradle.properties, 1 serializes for debugging).
tasks.named<Test>("integrationTest") {
    val threads = providers.gradleProperty("peekaboot.it.threads").getOrElse("2")
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", threads)
    systemProperty("junit.jupiter.execution.parallel.config.fixed.max-pool-size", threads)
}
