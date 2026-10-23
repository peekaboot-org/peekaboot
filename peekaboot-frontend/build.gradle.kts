import com.github.gradle.node.npm.task.NpmTask
import java.io.ByteArrayOutputStream

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
    // The pom's <peekaboot.node.version>, bare: frontend-maven-plugin is the one that wants the "v".
    version = pomProperty("peekaboot.node.version")
    download = true
    // Default is "install", which would rewrite package-lock.json from inside a build.
    npmInstallCommand = "ci"
}

// frontend-maven-plugin logs npm's output at INFO, which Maven's -q hides; Gradle passes it
// straight through. npm ci's install summary goes to stdout and only --silent hides it, which
// would hide npm's errors too, so stdout goes to INFO and stderr still carries the errors.
tasks.npmInstall {
    val output = ByteArrayOutputStream()
    execOverrides {
        standardOutput = output
    }
    doLast {
        logger.info(output.toString(Charsets.UTF_8))
    }
}

// A lint run that passes logs its output at INFO; one that fails prints everything npm and the
// linter wrote, since npm's own errors (a missing script, a broken package.json) come only on
// its output.
fun NpmTask.logOutputAtInfoUnlessItFails() {
    val output = ByteArrayOutputStream()
    ignoreExitValue = true
    execOverrides {
        standardOutput = output
        errorOutput = output
    }
    doLast {
        val exitValue = checkNotNull(result).exitValue
        if (exitValue != 0) {
            logger.error(output.toString(Charsets.UTF_8))
            throw GradleException("npm ${npmCommand.get().joinToString(" ")} exited with $exitValue")
        }
        logger.info(output.toString(Charsets.UTF_8))
    }
}

// One task per tool, mirroring the pom's lint-js / lint-css / lint-html executions and
// running the same three npm scripts. No declared outputs, so each always runs: a gate an
// up-to-date marker can skip is not a gate.
val lintJs = tasks.register<NpmTask>("lintJs") {
    group = "verification"
    description = "Runs ESLint over the frontend's ES modules."
    dependsOn(tasks.npmInstall)
    npmCommand = listOf("run", "lint:js")
    logOutputAtInfoUnlessItFails()
}

val lintCss = tasks.register<NpmTask>("lintCss") {
    group = "verification"
    description = "Runs stylelint over the frontend's stylesheets."
    dependsOn(tasks.npmInstall)
    shouldRunAfter(lintJs)
    npmCommand = listOf("run", "lint:css")
    logOutputAtInfoUnlessItFails()
}

val lintHtml = tasks.register<NpmTask>("lintHtml") {
    group = "verification"
    description = "Runs html-validate over the frontend's HTML."
    dependsOn(tasks.npmInstall)
    shouldRunAfter(lintCss)
    npmCommand = listOf("run", "lint:html")
    logOutputAtInfoUnlessItFails()
}

tasks.check {
    dependsOn(lintJs, lintCss, lintHtml)
}
