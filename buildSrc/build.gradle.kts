import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory

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

// The Spring Boot line is the root pom's spring-boot.version, for both the plugin here and
// the BOM the convention plugin imports. buildSrc is a build of its own and does not see
// what settings.gradle.kts reads, so read the pom directly.
val springBootVersion: String = XPathFactory.newInstance().newXPath().evaluate(
    "/project/properties/spring-boot.version",
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rootDir.parentFile.resolve("pom.xml")),
).trim().ifEmpty { throw GradleException("The root pom declares no <spring-boot.version>") }

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.4")
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:6.5.11")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.1")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:$springBootVersion")
    implementation("com.gorylenko.gradle-git-properties:gradle-git-properties:4.0.1")
    implementation("com.github.node-gradle:gradle-node-plugin:7.1.0")
}
