# Peekaboot - Development Reference

A Spring Boot starter that provides embedded application introspection—health, info, tracing, and diagnostics—through a lightweight web UI. No external infrastructure required.

## Vision

Two main interaction points:

### Debug Toolbar

Development-time toolbar inspired by Symfony Debug Toolbar/Profiler. Injected into HTML responses, attached to the bottom of the window using Shadow DOM (no interference with the host app).

**Collapsed state** shows request summary:
- HTTP status code (green: 2xx, yellow: 3xx, red: 4xx/5xx)
- Route hit
- Total request time
- Number of DB queries
- Error indicator (micrometer events or error logs during request)
- Trace ID

**Expanded state** shows:
- Full trace/span tree with log viewing per span (WARN=yellow, ERROR/FATAL=red highlighting)
- All logs for the trace
- DB queries: SQL, bound parameters, success/failed, result set size, execution time
- Micrometer `@Timed` annotation timings (related to spans where possible)

### App Insights

Standalone dashboard inspired by Spring Boot Admin. Exposes actuator data in a clean, navigable UI.

**Key characteristics:**
- Uses internal `WebEndpointDiscoverer` without filters (not exposed to application context)—actuator endpoints don't need HTTP exposure
- Shows build info, JVM info, environment, system properties
- UI/UX consistent with Debug Toolbar (tabs, same styling)
- Metrics from registry for current app state (uptime, memory)—no historical plotting
- Can run independently of Debug Toolbar (e.g., production use)
- Debug Toolbar always includes App Insights access
- Last N error traces viewable (configurable)—same detail as toolbar shows for current request

## Current Features

### Embedded Web UI
- Minimal Bulma CSS framework via webjars
- Dark/light theme with system preference detection and localStorage persistence
- Responsive: mobile, tablet, desktop
- Three tabs: Info, Health, Environment

### Security
- No direct actuator endpoint exposure required
- Sensitive values masked (password, secret, token, key, credential)
- System properties and environment variables filtered by default

### Configuration

```yaml
peekaboot:
  enabled: true           # Enable/disable (default: true)
  basePath: /peekaboot    # Base path for UI and API (default: /peekaboot)
```

Access UI at `http://localhost:8080/peekaboot/`, API at `http://localhost:8080/peekaboot/api`.

## Architecture

```
peekaboot/
├── peekaboot-backend/                    # Core backend logic
│   ├── DTOs for API responses
│   ├── Service layer aggregating actuator data
│   └── REST controller
├── peekaboot-frontend/                   # Frontend resources
│   ├── HTML (Bulma-based, responsive, tab-based UI)
│   ├── CSS (dark/light theme, minimal custom overrides)
│   ├── JavaScript (theme toggle, async data fetching)
│   └── Webjars (Bulma CSS framework)
├── peekaboot-spring-boot-autoconfigure/  # Auto-configuration
│   ├── Configuration properties
│   ├── Auto-configuration class
│   └── META-INF registration
├── peekaboot-spring-boot-starter/        # Dependency aggregator
├── peekaboot-tracing/                    # In-memory distributed tracing
└── peekaboot-example-app/                # Example/test application
```

### Web Endpoints

- `/peekaboot/api/*` — Structured JSON endpoints
- `/peekaboot/ui/*` — HTML endpoints

---

## Module: peekaboot-tracing

In-memory distributed tracing for Spring Boot applications. Works standalone or integrates with existing Brave/OpenTelemetry setups.

### Features

- **Standalone Tracer** — Full `io.micrometer.tracing.Tracer` implementation, no external infrastructure
- **Brave Integration** — `SpanHandler` adapter captures spans from Brave-based tracing
- **OpenTelemetry Integration** — `SpanExporter` adapter captures spans from OTel SDK
- **Caffeine-backed Storage** — Configurable trace/span capacity with automatic eviction
- **Query API** — Retrieve traces by ID, get recent traces, query spans in order
- **MDC Integration** — Automatic `traceId`/`spanId` propagation to SLF4J MDC
- **Zero Configuration** — Auto-configures based on classpath detection

### Usage

Add dependency:

```xml
<dependency>
    <groupId>net.osslabz</groupId>
    <artifactId>peekaboot-tracing</artifactId>
    <version>${peekaboot.version}</version>
</dependency>
```

Inject and use:

```java
@Autowired Tracer tracer;
@Autowired TraceQueryService queryService;

// Create spans
Span span = tracer.nextSpan().name("my-operation").start();
try (var scope = tracer.withSpan(span)) {
    span.tag("key", "value");
    // ... work
} finally {
    span.end();
}

// Query traces
List<TraceData> recent = queryService.getRecentTraces(10);
Optional<TraceData> trace = queryService.getTrace(traceId);
```

### Configuration

```yaml
peekaboot:
  tracing:
    enabled: true              # Enable/disable (default: true)
    max-traces: 1000           # Maximum traces to retain (default: 1000)
    max-spans-per-trace: 100   # Maximum spans per trace (default: 100)
```

### Tracing Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Application                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   ┌──────────┐    ┌──────────┐    ┌───────────────────┐    │
│   │  Brave   │    │   OTel   │    │  InMemoryTracer   │    │
│   │ Tracing  │    │   SDK    │    │   (standalone)    │    │
│   └────┬─────┘    └────┬─────┘    └─────────┬─────────┘    │
│        │               │                    │              │
│        ▼               ▼                    │              │
│   ┌──────────┐    ┌──────────┐              │              │
│   │  Brave   │    │   OTel   │              │              │
│   │ Handler  │    │ Exporter │              │              │
│   └────┬─────┘    └────┬─────┘              │              │
│        │               │                    │              │
│        └───────────────┼────────────────────┘              │
│                        ▼                                   │
│              ┌─────────────────┐                           │
│              │ InMemorySpanStore│ ◄── Caffeine Cache       │
│              └────────┬────────┘                           │
│                       ▼                                    │
│              ┌─────────────────┐                           │
│              │ TraceQueryService│                          │
│              └─────────────────┘                           │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### Auto-Configuration Behavior

| Classpath               | Beans Created                                              |
|-------------------------|-------------------------------------------------------------|
| No Brave, No OTel       | `InMemoryTracer`, `InMemorySpanStore`, `TraceQueryService`  |
| Brave present           | `BraveSpanHandler`, `InMemorySpanStore`, `TraceQueryService`|
| OpenTelemetry present   | `OtelSpanExporter`, `InMemorySpanStore`, `TraceQueryService`|
| Both present            | Both handlers + storage/query beans                         |

When Brave or OTel is detected, `InMemoryTracer` is **not** created—the existing tracer handles span creation while handlers capture finished spans.

### Module Structure

```
net.osslabz.peekaboot.tracing/
├── InMemoryTracer                    # Standalone Tracer implementation
├── autoconfigure/
│   ├── PeekabootTracingAutoConfiguration    # Core beans
│   ├── PeekabootTracingProperties           # Configuration properties
│   ├── StandaloneTracingAutoConfiguration   # InMemoryTracer (no Brave/OTel)
│   ├── BraveTracingAutoConfiguration        # Brave bridge
│   └── OtelTracingAutoConfiguration         # OTel bridge
├── bridge/
│   ├── brave/BraveSpanHandler        # brave.handler.SpanHandler adapter
│   └── otel/OtelSpanExporter         # SpanExporter adapter
├── context/
│   ├── InMemoryTraceContext          # TraceContext record
│   ├── InMemoryCurrentTraceContext   # ThreadLocal + MDC management
│   └── InMemoryScope                 # Scope with auto-restore
├── span/
│   ├── InMemorySpan                  # Span implementation
│   ├── InMemorySpanBuilder           # Span.Builder
│   └── InMemoryScopedSpan            # ScopedSpan
├── baggage/
│   ├── InMemoryBaggage               # Baggage implementation
│   └── InMemoryBaggageInScope        # BaggageInScope
├── store/
│   ├── InMemorySpanStore             # Caffeine-backed storage
│   ├── SpanData                      # Immutable span record
│   └── TraceData                     # Trace aggregate
└── query/
    └── TraceQueryService             # Query API
```

### Query API

```java
public class TraceQueryService {
    Optional<TraceData> getTrace(String traceId);
    List<SpanData> getSpans(String traceId);      // Ordered by creation
    List<TraceData> getRecentTraces(int limit);   // Newest first
    List<TraceData> getAllTraces();
    int getTraceCount();
}
```

### Data Models

**SpanData** — Immutable span record:
```java
record SpanData(
    String traceId, String spanId, String parentId,
    String name, Span.Kind kind,
    Instant startTime, Instant endTime, Duration duration,
    Map<String, String> tags, List<Event> events,
    String errorMessage, String errorClass,
    String remoteServiceName, String remoteIp, Integer remotePort,
    List<LinkData> links, long creationOrder
)
```

**TraceData** — Trace aggregate:
```java
record TraceData(
    String traceId,
    Instant startTime, Instant endTime, Duration duration,
    int spanCount, List<SpanData> spans
)
```

### Tracing Dependencies

| Dependency                              | Scope    | Purpose              |
|-----------------------------------------|----------|----------------------|
| `io.micrometer:micrometer-tracing`      | compile  | Core tracing API     |
| `com.github.ben-manes.caffeine:caffeine`| compile  | In-memory caching    |
| `org.slf4j:slf4j-api`                   | compile  | MDC integration      |
| `spring-boot-autoconfigure`             | optional | Auto-configuration   |
| `io.zipkin.brave:brave`                 | optional | Brave bridge         |
| `io.opentelemetry:opentelemetry-sdk-trace` | optional | OTel bridge       |

---

## Development Guidelines

### Implementation Principles

- **Multiple datasources** — Support multiple registered datasources (no single-datasource assumption)
- **Unobtrusive logging** — Minimize library log noise at startup and runtime
- **No external CSS/JS** — Avoid interference with host application
- **Shadow DOM isolation** — Debug toolbar must not affect host app styling/scripts
- **Generic data processing** — Driven by configuration, not hard-coded logic
- **Clean code** — Self-documenting code, comments only where complexity justifies

### Frontend

- Target: All major browsers except IE11
- Use modern CSS/JS features with broad support
- Clean separation: no embedded code in HTML
- Mobile-first responsive design

### Testing

- Unit tests for core logic
- Integration tests for Spring Boot auto-configuration
- Test different Spring Boot versions (3.5.x, 4.0.x)

### Requirements

- Java 21+
- Spring Boot 3.4+
- Maven 3.6+

---

## Quick Start

### 1. Add dependency

**Maven:**
```xml
<dependency>
    <groupId>net.osslabz</groupId>
    <artifactId>peekaboot-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'net.osslabz:peekaboot-spring-boot-starter:0.0.1-SNAPSHOT'
```

### 2. Enable Actuator

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 3. Configure (optional)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

### 4. Access

- UI: `http://localhost:8080/peekaboot/`
- API: `http://localhost:8080/peekaboot/api`

---

## Securing Peekaboot

```java
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/peekaboot/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
```

---

## License

Apache License 2.0

## Links

- [GitHub Repository](https://github.com/osslabz/peekaboot)
- [Issue Tracker](https://github.com/osslabz/peekaboot/issues)