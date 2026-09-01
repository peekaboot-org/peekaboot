import java.util.Properties

// Hosts the peekaboot.java-conventions precompiled script plugin - the Gradle
// equivalent of peekaboot-parent's shared build configuration. Third-party plugin
// versions are pinned here once; subprojects apply them by id only.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// The Spring Boot line is declared once, in the root gradle.properties, for both the
// plugin here and the BOM the convention plugin imports. buildSrc is a build of its own
// and does not inherit that file, so read it directly.
val springBootVersion = Properties()
    .apply { rootDir.parentFile.resolve("gradle.properties").inputStream().use { load(it) } }
    .getProperty("springBootVersion")

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.1")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:6.5.11")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.1")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:$springBootVersion")
    implementation("com.gorylenko.gradle-git-properties:gradle-git-properties:4.0.1")
}
