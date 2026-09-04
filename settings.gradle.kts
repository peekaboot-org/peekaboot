import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

// Parallel build system to the Maven reactor (see BUILD.md) - the module set and
// their artifactIds must stay in lockstep with the root pom.xml.
rootProject.name = "peekaboot"

include("peekaboot-test-support")
include("peekaboot-backend")
include("peekaboot-frontend")
include("peekaboot-spring-boot-autoconfigure")
include("peekaboot-spring-boot-starter")
include("peekaboot-testing-app")
include("peekaboot-coverage")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The poms are the system of record for the module version and the build instant:
// maven-release-plugin rewrites both on every release and never touches this build, so a
// hand-kept copy here would go stale the moment a release runs. BuildVersionLockstepTest
// fails if one reappears.
val pom: Element = DocumentBuilderFactory.newInstance()
    .newDocumentBuilder()
    .parse(rootDir.resolve("pom.xml"))
    .documentElement

fun childElement(parent: Element, name: String): Element {
    val children = parent.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child is Element && child.tagName == name) {
            return child
        }
    }
    throw GradleException("pom.xml has no <$name> under <${parent.tagName}>")
}

val pomVersion = childElement(pom, "version").textContent.trim()
val pomOutputTimestamp =
    childElement(childElement(pom, "properties"), "project.build.outputTimestamp").textContent.trim()

gradle.beforeProject {
    version = pomVersion
    extra["outputTimestamp"] = pomOutputTimestamp
}
