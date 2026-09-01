import net.ltgt.gradle.errorprone.errorprone

/*
 * The Gradle counterpart of peekaboot-parent: compiler setup (release 25, Error Prone,
 * configuration-processor-friendly), the four static-analysis gates at `check`, JaCoCo,
 * reproducible archives, and the unit/IT lifecycle split (`test` runs *Test only,
 * `integrationTest` runs *IT and hangs off `check`). Tool versions and config file
 * locations must stay in lockstep with the Maven build - Maven remains the system of
 * record until the Gradle build is proven equivalent.
 */
plugins {
    `java-library`
    jacoco
    checkstyle
    pmd
    id("com.github.spotbugs")
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

group = "org.peekaboot"
version = "0.0.5-SNAPSHOT"

java {
    withSourcesJar()
}

// Reproducible archives: stable entry order, no timestamps - the Gradle counterpart
// of Maven's project.build.outputTimestamp.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.encoding = "UTF-8"
}

// Maven <optional>true</optional> deps are visible to the module's own tests; Gradle's
// compileOnly is not. This restores that visibility without making anything transitive.
configurations.testImplementation.get().extendsFrom(configurations.compileOnly.get())

// Resolves the plain mockito-core jar so Test JVMs can register it as a Java agent,
// avoiding the inline-mock-maker's self-attach warning (mirrors the Maven argLine).
val mockitoAgent = configurations.create("mockitoAgent") {
    isTransitive = false
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.50.0")
    "mockitoAgent"("org.mockito:mockito-core:5.23.0")
    // Gradle 9 no longer puts the launcher on the test runtime classpath itself; the
    // version comes from the Spring Boot BOM each module imports.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${mockitoAgent.singleFile}")
    })
}

// Lifecycle split, same naming convention as surefire/failsafe: *Test at `test`,
// *IT (everything that boots a real application) at `integrationTest`.
tasks.test {
    // Surefire's include semantics, not a bare *IT exclusion: a class matching neither
    // pattern (e.g. ScreenshotCapture, which needs Docker) runs under NO lifecycle task,
    // exactly like in the Maven build.
    include("**/*Test.class", "**/*Tests.class")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the *IT classes (tests that boot a real application)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

jacoco {
    toolVersion = "0.8.15"
}

checkstyle {
    toolVersion = "14.0.0"
    configFile = rootProject.file("config/checkstyle.xml")
}

pmd {
    toolVersion = "7.26.0"
    ruleSets = listOf() // the built-in default ruleset must not leak in beside ours
    ruleSetFiles = files(rootProject.file("config/pmd-ruleset.xml"))
}

spotbugs {
    toolVersion = "4.10.4"
    excludeFilter = rootProject.file("config/spotbugs-exclude.xml")
}

// Main sources only, like the Maven gates: test data builders legitimately mirror the
// wide domain records they construct (checkstyle), and PMD/SpotBugs gate main too.
tasks.matching { it.name == "checkstyleTest" || it.name == "pmdTest" || it.name == "spotbugsTest" }
    .configureEach { enabled = false }

spotless {
    ratchetFrom("e05e0f97c3ab75a43bf493a8f841df74f4b648f1")
    java {
        palantirJavaFormat("2.97.0")
    }
}

// Mirrors the Maven spotless-apply-local profile: local builds auto-format before
// compiling, CI (CI=true) only checks so unformatted commits still fail there.
if (System.getenv("CI") == null) {
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(tasks.named("spotlessApply"))
    }
}
