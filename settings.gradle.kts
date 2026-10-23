import java.util.Properties
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

// Parallel build system to the Maven reactor (see BUILD.md), which is its system of record.
rootProject.name = "peekaboot"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The module list, the module version and every pom property come out of the poms rather
// than out of a copy here. maven-release-plugin rewrites the version and the build instant
// and never touches this build, and Dependabot bumps a pom property without a Gradle twin,
// so a hand-kept copy would go stale behind a green Gradle build.
fun pomOf(directory: File): Element = DocumentBuilderFactory.newInstance()
    .newDocumentBuilder()
    .parse(directory.resolve("pom.xml"))
    .documentElement

fun childElements(parent: Element, name: String): List<Element> {
    val children = parent.childNodes
    return (0 until children.length)
        .map { children.item(it) }
        .filterIsInstance<Element>()
        .filter { name == "*" || it.tagName == name }
}

fun childElement(parent: Element, name: String): Element =
    childElements(parent, name).singleOrNull()
        ?: throw GradleException("pom.xml has no single <$name> under <${parent.tagName}>")

fun propertiesOf(pom: Element): Map<String, String> =
    childElements(pom, "properties")
        .flatMap { childElements(it, "*") }
        .associate { it.tagName to it.textContent.trim() }

val rootPom = pomOf(rootDir)
val pomVersion = childElement(rootPom, "version").textContent.trim()
val rootPomProperties = propertiesOf(rootPom)

childElements(childElement(rootPom, "modules"), "module").forEach { include(it.textContent.trim()) }

// Each project sees its own pom's properties over the root pom's, the way a Maven module
// inherits them; BuildVersionLockstepTest holds the testing-app's copies of root values
// equal. Read through pomProperty in buildSrc.
gradle.beforeProject {
    version = pomVersion
    extra["pomProperties"] = Properties().apply {
        putAll(rootPomProperties)
        if (projectDir != rootDir) {
            putAll(propertiesOf(pomOf(projectDir)))
        }
    }
}
