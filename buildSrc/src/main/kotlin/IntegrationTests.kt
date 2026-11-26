import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the

/** A `verification` Test task over this module's compiled tests, run by `check`; [configuration] picks the classes. */
fun Project.registerIntegrationTest(
    name: String,
    description: String,
    configuration: Test.() -> Unit,
): TaskProvider<Test> {
    val integrationTest = tasks.register<Test>(name) {
        this.description = description
        group = "verification"
        val test = project.the<SourceSetContainer>().getByName("test")
        testClassesDirs = test.output.classesDirs
        classpath = test.runtimeClasspath
        configuration()
    }
    tasks.named("check") {
        dependsOn(integrationTest)
    }
    return integrationTest
}
