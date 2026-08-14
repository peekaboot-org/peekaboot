# Peekaboot

A Spring Boot starter that provides embedded application introspection through a lightweight web UI. View health, info, tracing, and diagnostics without external infrastructure.

## Features

- **App Insights Dashboard** - Standalone UI exposing actuator data: health, info, environment, loggers, flyway migrations, scheduled tasks
- **Debug Toolbar** - Development-time toolbar injected into HTML responses showing request traces, queries, and logs
- **In-Memory Tracing** - Micrometer-based distributed tracing with no external collector required. Integrates with OpenTelemetry via Spring Boot's tracing support
- **Zero Configuration** - Sensible defaults for full observability out of the box

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

No additional configuration required. Peekaboot auto-configures optimal defaults for observability.

## Configuration

All properties are optional. Peekaboot works out of the box with sensible defaults.

### Core Properties

| Property | Default | Description |
|----------|---------|-------------|
| `peekaboot.enabled` | `true` | Enable/disable Peekaboot entirely |
| `peekaboot.dev-toolbar` | `false` | Enable debug toolbar injection into HTML responses |

The UI and API are always served under the fixed `/peekaboot` prefix.

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
overrides them, and setting `peekaboot.enabled=false` skips them entirely.
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
