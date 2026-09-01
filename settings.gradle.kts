// Parallel build system to the Maven reactor (see BUILD.md) - the module set and
// their artifactIds must stay in lockstep with the root pom.xml.
rootProject.name = "peekaboot"

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
