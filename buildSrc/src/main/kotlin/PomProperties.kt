import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.extra

/** A property of this module's pom, or of the root pom if the module's has none, as settings.gradle.kts reads them. */
fun Project.pomProperty(name: String): String =
    (extra["pomProperties"] as Properties).getProperty(name)
        ?: throw GradleException("Neither the pom of $path nor the root pom declares <$name>")
