# Peekaboot

A Spring Boot starter that provides embedded application introspection through a lightweight web UI. View health, info, tracing, and diagnostics without external infrastructure.

## Features

- **App Insights Dashboard** - Standalone UI exposing actuator data: health, info, environment, loggers, flyway migrations, scheduled tasks
- **Debug Toolbar** - Development-time toolbar injected into HTML responses showing request traces, queries, and logs
- **In-Memory Tracing** - Micrometer-based distributed tracing with no external collector required. Integrates with OpenTelemetry via Spring Boot's tracing support
- **Zero Configuration** - Activates automatically in local development with sensible defaults for full observability; off by default everywhere else

## Quick Start

### 1. Add dependency

**Maven:**
```xml
<dependency>
    <groupId>org.peekaboot</groupId>
    <artifactId>peekaboot-spring-boot-starter</artifactId>
    <version>0.0.4-SNAPSHOT</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'org.peekaboot:peekaboot-spring-boot-starter:0.0.4-SNAPSHOT'
```

### 2. Access the dashboard

- UI: `http://localhost:8080/peekaboot/`
- API: `http://localhost:8080/peekaboot/api/`

No additional configuration required. Peekaboot activates automatically when the app runs locally (IDE, `spring-boot:run`/`bootRun`) and stays off everywhere else — see [When Each Feature Is Enabled](#when-each-feature-is-enabled).

## Configuration

All properties are optional. Peekaboot works out of the box with sensible defaults.

### Core Properties

| Property | Default | Description |
|----------|---------|-------------|
| `peekaboot.enabled` | auto-detected | Master switch — defaults to `true` only when running locally (see below), `false` everywhere else |
| `peekaboot.dev-toolbar` | `false` | Enable debug toolbar injection into HTML responses |
| `peekaboot.lifecycle.enabled` | `true` | Enable the startup summary log (environment, build info, server URLs, datasources) |

The UI and API are always served under the fixed `/peekaboot` prefix.

### When Each Feature Is Enabled

Peekaboot follows the same "local development only" heuristics as Spring Boot DevTools: the master switch `peekaboot.enabled` defaults to `true` only when the application runs on the `main` thread with the JDK's regular classpath classloader — i.e. launched from an IDE or via `spring-boot:run`/`bootRun`. It defaults to `false` for packaged jars (`java -jar`), wars in a servlet container, native images, AOT processing, and test runs (JUnit, Spring Boot tests, Cucumber). An explicit `peekaboot.enabled` setting — in `application.yml`, an environment variable, or a system property — always overrides the detection, in both directions.

| Feature | Property switch | Additional requirements |
|---------|-----------------|-------------------------|
| **Dashboard UI & API** | `peekaboot.enabled` (auto-detected) | Servlet web application; Spring Boot Actuator on the classpath (included in the starter) |
| **Debug Toolbar** | `peekaboot.enabled` **and** `peekaboot.dev-toolbar=true` (opt-in) | Servlet web application; a Micrometer `Tracer` bean (provided by `spring-boot-starter-opentelemetry`, included in the starter) |
| **In-Memory Tracing** | `peekaboot.enabled` **and** `peekaboot.tracing.enabled=true` (default) | OpenTelemetry SDK on the classpath for span capture (included in the starter) |
| **Startup Summary** | `peekaboot.enabled` **and** `peekaboot.lifecycle.enabled=true` (default) | — |
| **Observability Defaults** | `peekaboot.enabled` (auto-detected) | — |

Notes:

- When Peekaboot is disabled, nothing activates — no beans, no instrumentation, no data collection. The per-feature toggles (`peekaboot.tracing.enabled`, `peekaboot.lifecycle.enabled`, `peekaboot.dev-toolbar`) narrow things down within an enabled Peekaboot.
- The dashboard UI and API share a single switch — they cannot be enabled independently. The toolbar's expanded view loads its trace details from the API, so the toolbar effectively requires tracing to be active for meaningful content.
- Tests count as "not local development" — a `@SpringBootTest` that needs Peekaboot must set `peekaboot.enabled=true` explicitly.
- To deliberately run Peekaboot in a deployed environment (e.g. staging diagnosis), set `peekaboot.enabled=true`. Conversely, if you want the starter jar out of production builds entirely, exclude it via the Spring Boot Maven plugin's `excludes` or a Gradle `developmentOnly` dependency.

### Tracing Properties

| Property | Default | Description |
|----------|---------|-------------|
| `peekaboot.tracing.enabled` | `true` | Enable/disable in-memory tracing |
| `peekaboot.tracing.max-traces` | `1000` | Maximum traces to retain in the All bucket |
| `peekaboot.tracing.max-spans-per-trace` | `100` | Maximum spans per trace |
| `peekaboot.tracing.max-logs-per-trace` | `500` | Maximum logs per trace |
| `peekaboot.tracing.max-error-traces` | `100` | Maximum traces to retain in the Errors bucket |
| `peekaboot.tracing.max-slow-traces` | `100` | Maximum traces to retain in the Slow bucket |
| `peekaboot.tracing.slow-trace-threshold-ms` | `1000` | Trace duration (ms) at or above which a trace is classified as slow |

Traces are organized into three buckets: **All** (every captured trace, size-capped with a 30-minute TTL), **Errors** (traces with at least one error span or ERROR-level log), and **Slow** (traces at or above the slow-trace threshold). Errors and Slow entries survive All-bucket eviction. The trace API and dashboard accept a `bucket=all|errors|slow` filter (default `all`).

### UI Thresholds

| Property | Default | Description |
|----------|---------|-------------|
| `peekaboot.ui.tracing.slow-span-threshold-ms` | `100` | Threshold for marking spans as slow |
| `peekaboot.ui.tracing.very-slow-span-threshold-ms` | `500` | Threshold for marking spans as very slow |
| `peekaboot.ui.tracing.slow-query-threshold-ms` | `50` | Threshold for marking queries as slow |
| `peekaboot.ui.tracing.high-query-count-threshold` | `5` | Warning threshold for queries per span |
| `peekaboot.ui.tracing.high-trace-query-count-threshold` | `20` | Warning threshold for total trace queries |

### Example Configuration

```yaml
peekaboot:
  dev-toolbar: true                    # Enable debug toolbar
  tracing:
    max-traces: 500                    # Reduce memory usage
    slow-trace-threshold-ms: 2000      # Only flag traces at or above 2s
  ui:
    tracing:
      slow-span-threshold-ms: 200      # Adjust slow span threshold
```

## Auto-Configured Defaults

Peekaboot sets defaults for full observability. Any application property
overrides them, and they are skipped entirely when Peekaboot is disabled.
Note that some are security- or performance-relevant (health details,
environment info, 100% trace sampling, Hibernate statistics) - review them
before shipping the starter in a production profile:

```yaml
management:
  endpoint.health.show-details: always
  info.env.enabled: true
  info.java.enabled: true
  info.os.enabled: true
  info.process.enabled: true
  info.git.enabled: true
  tracing.sampling.probability: 1.0
  observations.annotations.enabled: true
  otlp.metrics.export.enabled: false
spring:
  jpa.properties.hibernate.generate_statistics: true

# third-party integrations (only apply if the library is present)
decorator.datasource.datasource-proxy:
  format-sql: true
  query.log-level: TRACE
logbook:
  predicate.include: [path: /api/**]
  format.style: http
  strategy: body-only-if-status-at-least
  minimum-status: 400
```

## Dashboard Tabs

| Tab | Content |
|-----|---------|
| **Health** | Component health status with details |
| **Info** | Build info, Git commit, Java/OS details |
| **Environment** | Application properties (secrets masked) |
| **Loggers** | Runtime log level configuration |
| **Flyway** | Database migration history |
| **Scheduled Tasks** | Cron jobs, fixed-rate, and fixed-delay tasks |
| **Traces** | Recent request traces with spans, queries, logs; filterable by All/Errors/Slow bucket with live counts |

## Debug Toolbar

When `peekaboot.dev-toolbar=true`, an unobtrusive toolbar is injected at the bottom of HTML responses:

**Collapsed view shows:**
- HTTP status code (color-coded)
- Request duration
- Database query count
- Trace ID

**Expanded view provides:**
- Full span tree with timing
- Database queries with SQL and duration
- Correlated log messages
- Request details

The toolbar uses Shadow DOM for complete style isolation from the host application.

## Security

Peekaboot does not expose raw actuator endpoints. All data is accessed through its own API with sensitive values automatically masked.

To secure the dashboard:

```java
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/peekaboot/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .build();
    }
}
```

## Requirements

- Java 25+
- Spring Boot 4.0+
- OpenTelemetry tracing (via `spring-boot-starter-opentelemetry`)

## License

Apache License 2.0
