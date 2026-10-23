plugins {
    id("peekaboot.java-conventions")
}

description = "Peekaboot Auto-configuration - Spring Boot auto-configuration"

val datasourceMicrometerVersion = pomProperty("datasource-micrometer.version")

dependencies {
    api(project(":peekaboot-backend"))
    api(project(":peekaboot-frontend"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    // EndpointExposureOutcomeContributor + EndpointExposure for the exposure contributor
    api("org.springframework.boot:spring-boot-actuator-autoconfigure")
    api("jakarta.annotation:jakarta.annotation-api")

    // OtelSpanExporter implements the SDK's SpanExporter, and backend keeps the SDK
    // compile-only, so without it SpotBugs cannot resolve that supertype
    compileOnly("io.opentelemetry:opentelemetry-sdk-trace")
    // compile-only reference for @ConditionalOnClass(HealthEndpoint)
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("org.springframework.boot:spring-boot-flyway")
    compileOnly("org.flywaydb:flyway-core")
    compileOnly("org.springframework.boot:spring-boot-liquibase")
    compileOnly("org.liquibase:liquibase-core")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    // ErrorMvcAutoConfiguration and ErrorAttributes for the error page
    compileOnly("org.springframework.boot:spring-boot-webmvc")
    // the Tomcat context customizer that keeps forwards working, see TomcatForwardResponseCustomizer
    compileOnly("org.springframework.boot:spring-boot-tomcat")
    compileOnly("org.apache.tomcat.embed:tomcat-embed-core")
    compileOnly("ch.qos.logback:logback-classic")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("io.micrometer:micrometer-observation")
    compileOnly("org.springframework.boot:spring-boot-micrometer-observation")
    // the Hibernate meters the insights panels read, bound from a session factory's statistics;
    // never brought into an application that has no Hibernate
    compileOnly("org.hibernate.orm:hibernate-core")

    // shared test support (LogCapture)
    testImplementation(project(":peekaboot-test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    // OpenTelemetry tracing for auto-configuration integration tests
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    // DataSourceAutoConfiguration + an in-memory H2: the lifecycle tests prove the
    // database metadata path against an auto-configured DataSource
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("com.h2database:h2")
    // QueryParameterAutoConfigurationTest proves the class condition with and without QueryContext
    testImplementation("net.ttddyy.observation:datasource-micrometer:$datasourceMicrometerVersion")
    // the SQL formatter's condition and the Hibernate meters, proven with Hibernate present and filtered away
    testImplementation("org.hibernate.orm:hibernate-core")
}
