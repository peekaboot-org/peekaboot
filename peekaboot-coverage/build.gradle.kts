plugins {
    base
    jacoco
}

description = "Peekaboot Coverage - reactor-wide coverage report and the coverage gate"

jacoco {
    toolVersion = "0.8.15"
}

/*
 * Same gate as the Maven module: line >= 0.90 and branch >= 0.75 over every published
 * class, measured on the merged execution data of the whole reactor. Gradle can point
 * the verification straight at the sibling modules' class directories, so the Maven
 * module's unpack-the-jars workaround is not needed here. The floors catch a
 * substantial regression, not a few uncovered lines - raise them deliberately, never
 * lower one to make a build pass.
 */
val gatedModules = listOf(
    "peekaboot-backend",
    "peekaboot-spring-boot-autoconfigure",
    "peekaboot-spring-boot-starter",
)

// testing-app contributes execution data only: its Playwright tests run peekaboot
// in-process, so its exec files carry backend coverage that would otherwise be lost.
val contributingModules = gatedModules + "peekaboot-testing-app"

val mergedExecData = files(contributingModules.map { module ->
    fileTree("${rootDir}/${module}/build/jacoco") { include("*.exec") }
})

val gatedClassDirs = files(gatedModules.map { "${rootDir}/${it}/build/classes/java/main" })
val gatedSourceDirs = files(gatedModules.map { "${rootDir}/${it}/src/main/java" })

val testTasks = contributingModules.flatMap { listOf(":$it:test", ":$it:integrationTest") }

val coverageGate = tasks.register<JacocoCoverageVerification>("coverageGate") {
    description = "Enforces the reactor-wide coverage floor on merged execution data."
    group = "verification"
    dependsOn(testTasks)
    executionData(mergedExecData)
    classDirectories.setFrom(gatedClassDirs)
    sourceDirectories.setFrom(gatedSourceDirs)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
    // Mirrors the Maven enforcer guard: without execution data a renamed module or a
    // skipped test run would read as a green gate. Fail loudly instead.
    doFirst {
        require(!mergedExecData.filter { it.exists() }.isEmpty) {
            "No coverage data - the gate cannot verify anything. Run a build that executes the tests."
        }
    }
}

val coverageReport = tasks.register<JacocoReport>("coverageReport") {
    description = "Renders the reactor-wide aggregate coverage report."
    group = "verification"
    dependsOn(testTasks)
    executionData(mergedExecData)
    classDirectories.setFrom(gatedClassDirs)
    sourceDirectories.setFrom(gatedSourceDirs)
    reports {
        html.required = true
        xml.required = true
    }
}

tasks.check {
    dependsOn(coverageGate, coverageReport)
}
