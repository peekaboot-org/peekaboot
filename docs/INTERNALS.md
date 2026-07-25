# Peekaboot Internals

Technical documentation for contributors and maintainers.

## Module Structure

```
peekaboot/
├── peekaboot-backend/                    # Core logic and APIs
├── peekaboot-frontend/                   # Static web resources
├── peekaboot-spring-boot-autoconfigure/  # Auto-configuration
├── peekaboot-spring-boot-starter/        # Dependency aggregator
└── peekaboot-example-app/                # Demo application
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Application                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   ┌──────────────────┐    ┌──────────────────┐                      │
│   │  DevToolbarFilter │    │RequestCaptureFilter│                   │
│   │  (HTML injection) │    │  (request metadata)│                   │
│   └────────┬─────────┘    └────────┬───────────┘                    │
│            │                       │                                 │
│            │    ┌──────────────────┴───────────┐                    │
│            │    │     Micrometer Tracer        │                    │
│            │    │  (tracer.currentSpan())      │                    │
│            │    └──────────────────────────────┘                    │
│            │                                                        │
│            └───────────┬───────────────────────────────────────────│
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │      ApplicationEventPublisher           │                      │
│   │    (Spring's standard event system)      │                      │
│   └────────────────────┬─────────────────────┘                      │
│                        │                                            │
│    ┌───────────────────┼───────────────────┐                       │
│    ▼                   ▼                   ▼                       │
│ SpanDataEvent   LogCapturedEvent   RequestCompletedEvent           │
│                        │                                            │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │           TraceDataStorage               │ ◄── Caffeine Cache  │
│   │   (traces, spans, logs, request data)    │     @EventListener   │
│   └────────────────────┬─────────────────────┘                      │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │          TraceQueryService               │                      │
│   │   (query API for stored traces)          │                      │
│   └────────────────────┬─────────────────────┘                      │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │          PeekabootController             │                      │
│   │   /peekaboot/api/* endpoints             │                      │
│   └──────────────────────────────────────────┘                      │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## peekaboot-backend

Core module containing all business logic.

### Package Structure

```
net.osslabz.peekaboot.backend/
├── actuator/raw/           # Typed beans for actuator responses
├── api/insights/           # API response DTOs
├── config/                 # Configuration properties
├── controller/             # REST endpoints
├── devtoolbar/             # Toolbar data extraction
├── domain/                 # Domain models (health, trace, etc.)
├── filter/                 # Servlet filters
├── lifecycle/              # Application startup hooks
├── log/                    # Logback appender
├── mapper/                 # Data transformation
│   ├── actuator/           # Actuator → domain mappers
│   └── trace/              # Trace processing
├── service/                # Business services
├── tracing/                # In-memory tracing
│   ├── autoconfigure/      # Tracing auto-configuration
│   ├── bridge/otel/        # OpenTelemetry span exporter
│   ├── event/              # Spring application events
│   ├── interceptor/        # Tracing handler interceptor
│   ├── query/              # Query API
│   └── store/              # Caffeine-backed storage (TraceDataStorage)
└── util/                   # Utilities (masking, etc.)
```

### Tracing Flow

1. **Span Capture**: `OtelSpanExporter` receives spans from OpenTelemetry SDK
2. **Event Publishing**: Publishes `SpanDataEvent` via Spring's `ApplicationEventPublisher`
3. **Storage**: `TraceDataStorage` listens via `@EventListener` and stores in Caffeine cache
4. **Log Correlation**: `PeekabootLogbackAppender` captures logs using Micrometer's `Tracer.currentSpan()` for trace ID
5. **Request Metadata**: `RequestCaptureFilter` uses `Tracer.currentSpan()` to correlate request details
6. **Query**: `TraceQueryService` provides access to stored data

### Servlet Filters

| Filter | Purpose |
|--------|---------|
| `DevToolbarFilter` | Injects toolbar HTML/JS into responses |
| `RequestCaptureFilter` | Captures request/response metadata for traces |

Both use `FilterPathMatcher` to skip static resources and peekaboot's own endpoints.

### Server-Timing Header

`RequestCaptureFilter` sets a `Server-Timing` response header carrying the current
trace context in W3C `traceparent` form, before invoking the filter chain:

```
Server-Timing: trace;desc="00-<traceId>-<spanId>-<traceFlags>"
```

Setting it before the chain runs ensures the header is present even when downstream
handling commits the response early. Clients and tooling (e.g. the Swagger UI
toolbar) read it to correlate a response with its captured trace.

### BFF Pattern

The backend implements a Backend-for-Frontend pattern:

1. **Raw Actuator Data**: `PeekabookActuatorService` calls actuator endpoints internally
2. **Typed Parsing**: `ActuatorRawMapper` converts raw JSON to typed beans
3. **Domain Mapping**: Individual mappers transform to domain models
4. **Aggregation**: `ActuatorInsightsService` combines all data for the dashboard

```
Actuator Endpoints → Raw Beans → Domain Models → API Response
     (JSON)         (typed)      (clean DTOs)    (dashboard)
```

## peekaboot-frontend

Static resources served from `/peekaboot/ui/`.

### File Structure

```
static/peekaboot/ui/
├── assets/
│   └── peekaboot.css       # All styling (dark/light themes)
├── dashboard/
│   ├── index.html          # Main dashboard page
│   └── peekaboot.js        # Dashboard logic
├── shared/
│   └── peekaboot-utils.js  # Shared utilities
├── toolbar/
│   └── toolbar.js          # Collapsed toolbar bar (loaded via injected script tag)
└── trace-detail/
    └── trace-detail.js     # Standalone trace viewer
```

### Design Principles

- **No build step**: Plain HTML/CSS/JS
- **Shadow DOM**: Toolbar isolated from host app styles
- **Mobile-first**: Responsive design
- **Lazy loading**: Trace-detail overlay JS loaded only on first use
- **Theme support**: CSS variables for dark/light modes

## peekaboot-spring-boot-autoconfigure

Auto-configuration classes that wire everything together.

| Class | Purpose |
|-------|---------|
| `PeekabootAutoConfiguration` | Core beans, component scan |
| `DevToolbarAutoConfiguration` | Toolbar filter registration |
| `PeekabootLifecycleAutoConfiguration` | Startup listeners |
| `PeekabootDefaultsEnvironmentPostProcessor` | Default property values |

### Conditional Loading

Auto-configuration uses Spring Boot conditionals:

```java
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "peekaboot", name = "dev-toolbar", havingValue = "true")
@ConditionalOnClass(TraceQueryService.class)
```

### Default Properties

`PeekabootDefaultsEnvironmentPostProcessor` loads `peekaboot-defaults.yml` with lowest precedence, enabling full observability while allowing apps to override.

## Tracing Integration

### OpenTelemetry Bridge

When OpenTelemetry is on the classpath, `OtelSpanExporter` is registered:

```java
@Bean
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
public OtelSpanExporter otelSpanExporter(TraceDataStorage storage, ApplicationEventPublisher eventPublisher) {
    return new OtelSpanExporter(storage, eventPublisher);
}
```

The exporter:
- Receives finished spans from OTel SDK
- Filters out peekaboot's own requests (`/peekaboot/**`, `/actuator/**`)
- Converts to `SpanData` and publishes `SpanDataEvent` via Spring's
  `ApplicationEventPublisher`; `TraceDataStorage` stores it via an
  `@EventListener`

### Micrometer Tracer Integration

Peekaboot uses Micrometer's `Tracer` API (not MDC) for trace correlation:

```java
// Get current trace context
Span currentSpan = tracer.currentSpan();
if (currentSpan != null) {
    String traceId = currentSpan.context().traceId();
    String spanId = currentSpan.context().spanId();
}
```

This ensures compatibility with Spring Boot's tracing auto-configuration and works correctly regardless of whether MDC propagation is enabled.

### Log Capture

`PeekabootLogbackAppender` captures log events with trace correlation:

1. Registered via `LogbackAppenderRegistrar` bean in `DevToolbarAutoConfiguration`
2. Publishes `LogCapturedEvent` via Spring's `ApplicationEventPublisher`
3. `TraceDataStorage` receives events via `@EventListener` and stores by traceId
4. Logs are associated with spans and included in trace detail views

### Span Deduplication

`SpanDeduplicator` runs in `TraceInsightsService` before spans are mapped to a
`TraceTree`, for both the trace list and single-trace views. It removes child
spans that duplicate their parent — same span name and identical tags, ignoring
service-identifier keys (`peer.service`, `jdbc.datasource.name`) — which occur
when a single operation is instrumented by more than one layer. Children of a
removed span are re-parented to the nearest surviving ancestor so the tree stays
connected.

## Data Models

### Trace Domain

```
TraceData
├── traceId: String
├── startTime, endTime, duration
├── spanCount: int
└── spans: List<SpanData>

SpanData
├── traceId, spanId, parentId
├── name, kind
├── startTime, endTime, duration
├── tags: Map<String, String>
├── events: List<Event>
└── errorMessage, errorClass
```

### Insights Domain

```
ActuatorInsightsResponse
├── health: HealthInfo
├── application: ApplicationInfo
├── environment: EnvironmentInfo
├── loggers: LoggersInfo
├── flyway: FlywayInfo
├── scheduledTasks: ScheduledTasksInfo
├── config: ConfigInfo
└── traces: List<TraceInfo>
```

## Testing

### Test Categories

| Type | Location | Purpose |
|------|----------|---------|
| Unit | `*Test.java` | Pure logic, no Spring context |
| Integration | `*IntegrationTest.java` | Spring Boot context |
| AutoConfig | `*AutoConfigurationTest.java` | Conditional bean loading |

### Testing Auto-Configuration

Use `ApplicationContextRunner` with `FilteredClassLoader` for unit tests:

```java
new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(DevToolbarAutoConfiguration.class))
    .withClassLoader(new FilteredClassLoader(TraceQueryService.class))
    .run(context -> assertThat(context).doesNotHaveBean("devToolbarFilter"));
```

For integration tests that verify auto-configuration ordering, use real OpenTelemetry:

```java
@SpringBootTest(classes = TestApplication.class, webEnvironment = RANDOM_PORT)
@ActiveProfiles("integration")
class DevToolbarAutoConfigurationIntegrationTest {
    // Uses spring-boot-starter-opentelemetry for real Tracer bean
    // Verifies auto-configuration creates beans in correct order
}
```

## Build

```bash
# Full build with tests
mvn clean install

# Quick compile
mvn clean compile -DskipTests

# Run example app
cd peekaboot-example-app && mvn spring-boot:run
```

## Key Design Decisions

1. **No external dependencies for tracing**: Works without Zipkin, Jaeger, or other collectors
2. **Micrometer-based**: Uses Micrometer's `Tracer` API for trace context, not MDC
3. **Spring Events**: Uses `ApplicationEventPublisher` instead of custom event bus
4. **Unified Storage**: Single `TraceDataStorage` class handles spans, logs, and request data
5. **Actuator not exposed**: All data accessed through internal `WebEndpointDiscoverer`
6. **Caffeine for storage**: Bounded memory with automatic eviction
7. **Shadow DOM**: Toolbar cannot interfere with host application
8. **Lowest-priority defaults**: Apps can always override peekaboot settings

## Auto-Configuration Ordering

`DevToolbarAutoConfiguration` requires specific ordering to ensure the `Tracer` bean exists:

```java
@AutoConfiguration(
    after = PeekabootAutoConfiguration.class,
    afterName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration"
)
```

The `afterName` attribute (string-based) is used instead of class reference to avoid compile-time dependency on the tracing autoconfigure module.