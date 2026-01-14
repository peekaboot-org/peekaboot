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
│   │   DevToolbarFilter│    │ RequestCaptureFilter│                  │
│   │   (HTML injection)│    │   (trace context)   │                  │
│   └────────┬─────────┘    └────────┬───────────┘                    │
│            │                       │                                 │
│            └───────────┬───────────┘                                │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │              TraceEventBus               │                      │
│   │  (publish/subscribe for trace events)    │                      │
│   └────────────────────┬─────────────────────┘                      │
│                        │                                            │
│    ┌───────────────────┼───────────────────┐                       │
│    ▼                   ▼                   ▼                       │
│ SpanCompleted    LogCaptured     RequestCompleted                  │
│                        │                                            │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │          InMemorySpanStore               │ ◄── Caffeine Cache  │
│   │   (traces, spans, logs, request data)    │                      │
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
│   ├── bridge/otel/        # OpenTelemetry integration
│   ├── event/              # Event bus
│   ├── query/              # Query API
│   └── store/              # Caffeine-backed storage
└── util/                   # Utilities (masking, etc.)
```

### Tracing Flow

1. **Span Capture**: `OtelSpanExporter` receives spans from OpenTelemetry SDK
2. **Storage**: Spans stored in `InMemorySpanStore` (Caffeine cache with LRU eviction)
3. **Event Publishing**: `TraceEventBus` notifies listeners of new spans
4. **Log Correlation**: `PeekabootLogbackAppender` captures logs with MDC traceId/spanId
5. **Query**: `TraceQueryService` provides access to stored data

### Servlet Filters

| Filter | Purpose |
|--------|---------|
| `DevToolbarFilter` | Injects toolbar HTML/JS into responses |
| `RequestCaptureFilter` | Captures request/response metadata for traces |

Both use `FilterPathMatcher` to skip static resources and peekaboot's own endpoints.

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
│   └── toolbar.js          # Debug toolbar (lazy-loaded)
└── trace-detail/
    └── trace-detail.js     # Standalone trace viewer
```

### Design Principles

- **No build step**: Plain HTML/CSS/JS
- **Shadow DOM**: Toolbar isolated from host app styles
- **Mobile-first**: Responsive design
- **Lazy loading**: Toolbar JS loaded only when expanded
- **Theme support**: CSS variables for dark/light modes

## peekaboot-spring-boot-autoconfigure

Auto-configuration classes that wire everything together.

| Class | Purpose |
|-------|---------|
| `PeekabootAutoConfiguration` | Core beans, component scan |
| `DevToolbarAutoConfiguration` | Toolbar filter registration |
| `PeekabootLifecycleAutoConfiguration` | Startup listeners |
| `DataSourceProxyLoggingAutoConfiguration` | SQL query capture |
| `DataSourceProxyObservationAutoConfiguration` | Query tracing spans |
| `PeekabootDefaultsEnvironmentPostProcessor` | Default property values |

### Conditional Loading

Auto-configuration uses Spring Boot conditionals:

```java
@ConditionalOnProperty(prefix = "peekaboot", name = "dev-toolbar", havingValue = "true")
@ConditionalOnBean(TraceQueryService.class)
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.SpanProcessor")
```

### Default Properties

`PeekabootDefaultsEnvironmentPostProcessor` loads `peekaboot-defaults.yml` with lowest precedence, enabling full observability while allowing apps to override.

## Tracing Integration

### OpenTelemetry Bridge

When OpenTelemetry is on the classpath, `OtelSpanExporter` is registered:

```java
@Bean
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
public OtelSpanExporter peekabootOtelExporter(InMemorySpanStore store, TraceEventBus bus) {
    return new OtelSpanExporter(store, bus);
}
```

The exporter:
- Receives finished spans from OTel SDK
- Filters out peekaboot's own requests (`/peekaboot/**`, `/actuator/**`)
- Converts to `SpanData` and stores in `InMemorySpanStore`
- Publishes `SpanCompletedEvent` to `TraceEventBus`

### Log Capture

`PeekabootLogbackAppender` captures log events with trace correlation:

1. Registered via `logbackAppenderRegistrar` bean
2. Reads `traceId` from MDC
3. Publishes `LogCapturedEvent` with timestamp, level, message
4. Stored in `TraceLogStore` (Caffeine cache, keyed by traceId)

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

Use `ApplicationContextRunner` with `FilteredClassLoader`:

```java
new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(DevToolbarAutoConfiguration.class))
    .withClassLoader(new FilteredClassLoader(TraceQueryService.class))
    .run(context -> assertThat(context).doesNotHaveBean("devToolbarFilter"));
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
2. **Actuator not exposed**: All data accessed through internal `WebEndpointDiscoverer`
3. **Caffeine for storage**: Bounded memory with automatic eviction
4. **Event-driven**: Loose coupling via `TraceEventBus`
5. **Shadow DOM**: Toolbar cannot interfere with host application
6. **Lowest-priority defaults**: Apps can always override peekaboot settings
