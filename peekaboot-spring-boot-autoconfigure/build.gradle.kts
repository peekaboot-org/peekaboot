plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Auto-configuration - Spring Boot auto-configuration"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    api(project(":peekaboot-backend"))
    api(project(":peekaboot-frontend"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    // EndpointExposureOutcomeContributor + EndpointExposure for the exposure contributor
    api("org.springframework.boot:spring-boot-actuator-autoconfigure")
    api("jakarta.annotation:jakarta.annotation-api")

    // compile-only reference for @ConditionalOnClass(HealthEndpoint)
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("io.micrometer:micrometer-observation")
    compileOnly("org.springframework.boot:spring-boot-micrometer-observation")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    // OpenTelemetry tracing for auto-configuration integration tests
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    testImplementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("com.h2database:h2")
}
