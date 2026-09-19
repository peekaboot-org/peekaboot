import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("peekaboot.java-conventions")
    id("com.github.node-gradle.node")
}

description = "Peekaboot Frontend - Embedded web UI resources"

// The node plugin registers an Ivy repository on this project to fetch the Node
// distribution. Gradle's default PREFER_PROJECT mode then stops consulting the
// repositories in settings.gradle.kts here, and every other plugin's dependencies - the
// palantir formatter first - stop resolving. Declaring it back is the whole fix; the
// settings block still serves the other six modules.
repositories {
    mavenCentral()
}

// No sources and no build step: plain ES modules and CSS under src/main/resources,
// copied into the jar as-is. The convention plugin's Java gates all no-op on the empty
// Java source set. What this module does gate is the JS, CSS and HTML themselves - the
// Gradle half of the frontend-maven-plugin block in pom.xml, which is the system of
// record (BUILD.md, Lockstep).

node {
    // Same pin as <peekaboot.node.version>; frontend-maven-plugin spells it "v24.21.0",
    // this plugin wants it bare. BuildVersionLockstepTest compares the two.
    version = "24.21.0"
    download = true
    // Default is "install", which would rewrite package-lock.json from inside a build.
    npmInstallCommand = "ci"
}

// One task per tool, mirroring the pom's lint-js / lint-css / lint-html executions and
// running the same three npm scripts. No declared outputs, so each always runs: a gate an
// up-to-date marker can skip is not a gate.
val lintJs = tasks.register<NpmTask>("lintJs") {
    group = "verification"
    description = "Runs ESLint over the frontend's ES modules."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "lint:js")
}

val lintCss = tasks.register<NpmTask>("lintCss") {
    group = "verification"
    description = "Runs stylelint over the frontend's stylesheets."
    dependsOn(tasks.npmInstall)
    shouldRunAfter(lintJs)
    npmCommand = listOf("run", "lint:css")
}

val lintHtml = tasks.register<NpmTask>("lintHtml") {
    group = "verification"
    description = "Runs html-validate over the frontend's HTML."
    dependsOn(tasks.npmInstall)
    shouldRunAfter(lintCss)
    npmCommand = listOf("run", "lint:html")
}

tasks.check {
    dependsOn(lintJs, lintCss, lintHtml)
}
