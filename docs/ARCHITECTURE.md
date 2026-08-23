# Peekaboot Architecture

Technical documentation for contributors and maintainers.

> This file and its siblings in `docs/` are for people changing the code. Consumer
> documentation — quick start, configuration, security, the dashboard tour — lives at
> [peekaboot.org](https://peekaboot.org).

## Module Structure

```
peekaboot/
├── peekaboot-backend/                    # Core logic and APIs
├── peekaboot-frontend/                   # Static web resources
├── peekaboot-spring-boot-autoconfigure/  # Auto-configuration
├── peekaboot-spring-boot-starter/        # Dependency aggregator
└── peekaboot-testing-app/                # Sample app + UI tests
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
│   │        TraceStoreEventListener           │ ◄── @EventListener   │
│   │      (forwards events to the store)      │                      │
│   └────────────────────┬─────────────────────┘                      │
│                        ▼                                            │
│   ┌──────────────────────────────────────────┐                      │
│   │    TraceStore (InMemoryTraceStore)       │ ◄── Caffeine Cache   │
│   │   All / Errors / Slow buckets            │     + bounded maps   │
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
org.peekaboot.backend/
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
│   └── store/              # TraceStore, InMemoryTraceStore, TraceBucket, TraceStoreEventListener
└── util/                   # Utilities (masking, etc.)
```

### Tracing Flow

1. **Span Capture**: `OtelSpanExporter` receives spans from OpenTelemetry SDK
2. **Event Publishing**: Publishes `SpanDataEvent` via Spring's `ApplicationEventPublisher`
3. **Storage**: `TraceStoreEventListener` listens via `@EventListener` and forwards to `TraceStore` (`InMemoryTraceStore`), which stores in a Caffeine cache (All bucket) plus bounded maps for the Errors and Slow buckets
4. **Log Correlation**: `PeekabootLogbackAppender` captures logs using Micrometer's `Tracer.currentSpan()` for trace ID
5. **Request Metadata**: `RequestCaptureFilter` uses `Tracer.currentSpan()` to correlate request details
6. **Query**: `TraceInsightsService` and `TraceRawService` query `TraceStore` directly by `TraceBucket` (ALL/ERRORS/SLOW)

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

1. **Raw Actuator Data**: `PeekabootActuatorService` invokes actuator endpoints in-process (see below)
2. **Typed Parsing**: `ActuatorRawMapper` converts raw JSON to typed beans
3. **Domain Mapping**: Individual mappers transform to domain models
4. **Aggregation**: `ActuatorInsightsService` combines all data for the dashboard

```
Actuator Endpoints → Raw Beans → Domain Models → API Response
     (JSON)         (typed)      (clean DTOs)    (dashboard)
```

### In-Process Actuator Invocation

Peekaboot never calls `/actuator/*` over HTTP. `PeekabootActuatorService` builds
its own `WebEndpointDiscoverer` with empty endpoint filters — bypassing
`management.endpoints.web.exposure` *filtering* — and invokes each endpoint's
READ operation directly (`operation.invoke(...)`). Data therefore flows without
any actuator endpoint being reachable over the web.

Bypassing exposure filtering is not enough on its own: Spring Boot only
*creates* an endpoint bean when `@ConditionalOnAvailableEndpoint` matches, i.e.
the endpoint is accessible **and** exposed via web, JMX (only when
`spring.jmx.enabled=true`), or a custom contributor. Spring Boot's default web
exposure is `health` only, so without help the `env`, `configprops`, `loggers`,
`flyway`, and `scheduledtasks` beans would never exist.

`PeekabootEndpointExposureOutcomeContributor` (registered under
`EndpointExposureOutcomeContributor` in `META-INF/spring.factories`, a Spring
Boot 3.4+ extension point) closes that gap: while `peekaboot.enabled=true` it
reports every web-capable endpoint as exposed, so the beans are created. The
regular HTTP mapping under `/actuator` still applies the
`management.endpoints.web.exposure` property, so this does **not** expose any
endpoint over the web — with Spring defaults only `/actuator/health` is
reachable via HTTP while the dashboard has full data.

See [peekaboot.org/docs/security](https://peekaboot.org/docs/security/) for what this
exposure model means in practice for securing a deployment.

## peekaboot-frontend

Static resources served from `/peekaboot/ui/`, backing three UI surfaces — the
standalone dashboard, the dev toolbar injected into host-app pages, and the trace-detail
overlay — that share one design system. See `peekaboot-frontend/README.md` for the full
picture (the shared-layer split, the shadow-DOM delivery mechanism, theme resolution,
accessibility invariants, and the ids the test suite depends on); this section covers
only the file layout and the headline decisions.

### File Structure

```
static/peekaboot/ui/
├── assets/
│   ├── tokens.css          # Design tokens (--pk-* custom properties) — the re-theme point
│   ├── base.css             # Reset + bare element defaults
│   └── components.css       # .pk-* component primitives (badge, group, kv, meter, tabs, ...)
├── shared/
│   ├── api.js                # createClient() — fetch wrapper with per-path generation guards
│   ├── components.js         # JS builders behind the .pk-* primitives
│   ├── format.js              # Duration/byte/date formatting
│   ├── markup.js               # escapeHtml, highlightText, isSensitiveKey
│   ├── root-actions.js         # Root action type -> icon/label map
│   ├── severity.js             # Duration/health severity thresholds (SLOW_MS, VERY_SLOW_MS)
│   ├── shadow-styles.js        # attachSharedStyles() — links the shared sheets into a shadow root
│   ├── span-names.js           # spanId -> name lookup, shared by overlay tabs
│   └── theme.js                # localStorage-backed theme resolution shared across surfaces
├── dashboard/
│   ├── index.html            # Dashboard document
│   ├── dashboard.css         # Dashboard-only chrome
│   ├── main.js                # Bootstrap: tab registry, hash routing, auto-refresh
│   └── tabs/*.js               # One module per tab (8), each exporting id/label/render
├── trace-detail/
│   ├── trace-detail.css      # Overlay chrome
│   ├── trace-detail.js        # Shell: open()/close(), tab wiring (shadow-rooted)
│   └── tabs/*.js               # One module per tab (4: request, spans, queries, logs)
└── toolbar/
    ├── toolbar.css            # Toolbar chrome
    └── toolbar.js              # Collapsed bar (shadow-rooted; lazy-imports trace-detail.js)
```

### Design Principles

- **No build step**: Plain HTML/CSS/JS, ES modules
- **Shared design system, three surfaces**: `assets/tokens.css`/`base.css`/`components.css`
  are consumed by the dashboard document directly and by the toolbar's and overlay's
  shadow roots via `attachSharedStyles()` — a "doubled selector" (`:root, :host { ... }`)
  lets the identical stylesheet apply in both contexts. Before this, all three surfaces
  duplicated palettes, `escapeHtml`, duration thresholds, and the collapsible-group CSS
  independently.
- **Shadow DOM**: Toolbar and trace-detail overlay isolated from host app styles
- **Mobile-first**: Responsive design
- **Lazy loading**: Trace-detail overlay JS loaded only on first use (dynamic `import()`
  from `toolbar.js`; eager static import from `dashboard/main.js`)
- **Theme support**: `--pk-*` CSS custom properties for dark/light modes, resolved once
  in `shared/theme.js` from `localStorage['peekaboot-theme']` (falling back to
  `prefers-color-scheme`) and shared across all three same-origin surfaces

See [peekaboot.org/docs/theming](https://peekaboot.org/docs/theming/) for how a consuming
application overrides `tokens.css` to re-theme all three surfaces.

## peekaboot-spring-boot-autoconfigure

Auto-configuration classes that wire everything together.

| Class | Purpose |
|-------|---------|
| `PeekabootAutoConfiguration` | Core beans, component scan |
| `DevToolbarAutoConfiguration` | Toolbar filter registration |
| `PeekabootLifecycleAutoConfiguration` | Startup listeners |
| `PeekabootTracingAutoConfiguration` | Tracing properties and store |
| `OtelTracingAutoConfiguration` | OpenTelemetry span exporter |
| `TracingInterceptorAutoConfiguration` | Tracing handler interceptor |
| `PeekabootDefaultsEnvironmentPostProcessor` | `peekaboot.enabled` local-dev detection + default property values (via `spring.factories`) |
| `PeekabootEndpointExposureOutcomeContributor` | Makes actuator endpoint beans available without web/JMX exposure (via `spring.factories`) |

### Conditional Loading

Auto-configuration uses Spring Boot conditionals. Only `DevToolbarAutoConfiguration` and
`TracingInterceptorAutoConfiguration` carry the servlet guard:

```java
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "peekaboot", name = "dev-toolbar", havingValue = "true")
@ConditionalOnClass(TraceStore.class)
```

`PeekabootAutoConfiguration` &mdash; the class that component-scans the servlet-only
`PeekabootWebConfig` and registers the controllers, services and actuator wiring &mdash;
does **not** carry `@ConditionalOnWebApplication`, nor do
`PeekabootLifecycleAutoConfiguration`, `PeekabootTracingAutoConfiguration` or
`OtelTracingAutoConfiguration`. See [Known defects](#known-defects) below.

There is no `matchIfMissing` fallback for `peekaboot.enabled` — the default
comes from `PeekabootDefaultsEnvironmentPostProcessor`, which detects local
development and adds the property with lowest precedence.

### Default Properties

`PeekabootDefaultsEnvironmentPostProcessor` loads two yml resources with lowest
precedence, both overridable by an app's own `application.yml`:

- `peekaboot-defaults.yml` &mdash; enables full observability, but only when Peekaboot is
  enabled; skipped entirely otherwise.
- `peekaboot-no-push-defaults.yml` &mdash; applies unconditionally, even when Peekaboot
  itself is disabled, to keep telemetry from leaving the process by default.

## Tracing Integration

### OpenTelemetry Bridge

When OpenTelemetry is on the classpath, `OtelSpanExporter` is registered:

```java
@Bean
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.export.SpanExporter")
public OtelSpanExporter otelSpanExporter(TraceStore storage, ApplicationEventPublisher eventPublisher) {
    return new OtelSpanExporter(storage, eventPublisher);
}
```

The exporter:
- Receives finished spans from OTel SDK
- Filters out peekaboot's own requests (`/peekaboot/**`, `/actuator/**`)
- Converts to `SpanData` and publishes `SpanDataEvent` via Spring's
  `ApplicationEventPublisher`; `TraceStoreEventListener` receives it via an
  `@EventListener` and forwards it to `TraceStore`

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
3. `TraceStoreEventListener` receives events via `@EventListener` and forwards them to `TraceStore`, which stores by traceId
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
├── errorMessage, errorClass
├── remoteServiceName, remoteIp, remotePort
├── links: List<LinkData>
└── creationOrder: long
```

### Insights Domain

```
ActuatorInsightsResponse
├── application: ApplicationInfo
├── runtime: RuntimeInfo
├── dataSources: List<DataSourceInfo>
├── health: HealthInfo
├── environment: EnvironmentInfo
├── loggers: LoggersInfo
├── flyway: FlywayInfo
├── config: ConfigInfo
├── scheduledTasks: ScheduledTasksInfo
└── server: ServerInfo
```

## Testing

### Test Categories

| Type | Location | Purpose |
|------|----------|---------|
| Unit | `*Test.java` | Pure logic, no Spring context |
| Integration | `*IntegrationTest.java` | Spring Boot context |
| AutoConfig | `*AutoConfigurationTest.java` | Conditional bean loading |

Any test that boots a Spring context — `@SpringBootTest`, including the Playwright UI
suite under `org.peekaboot.testingapp.ui` — lives in `peekaboot-testing-app`, not
`peekaboot-backend`. `peekaboot-backend`'s own test suite is pure unit tests with no
Spring context; `peekaboot-frontend` currently has no test sources of its own (its
behaviour is covered by the Playwright suite in `peekaboot-testing-app`, which boots the
sample app and drives the real dashboard/toolbar/overlay in a headless browser).

### Testing Auto-Configuration

Use `ApplicationContextRunner` with `FilteredClassLoader` for unit tests:

```java
new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(DevToolbarAutoConfiguration.class))
    .withClassLoader(new FilteredClassLoader(TraceStore.class))
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

## Key Design Decisions

1. **No external dependencies for tracing**: Works without Zipkin, Jaeger, or other collectors
2. **Micrometer-based**: Uses Micrometer's `Tracer` API for trace context, not MDC
3. **Spring Events**: Uses `ApplicationEventPublisher` instead of custom event bus
4. **Bucketed Storage**: `InMemoryTraceStore` handles spans, logs, and request data across three buckets — All (Caffeine cache), Errors, and Slow (bounded maps holding references into the All bucket's bundles, surviving its eviction). See [peekaboot.org/docs/tracing](https://peekaboot.org/docs/tracing/) for bucket sizing, the slow-trace threshold, and the `bucket=all|errors|slow` filter.
5. **Actuator not web-exposed**: All data accessed in-process through an internal `WebEndpointDiscoverer`; `PeekabootEndpointExposureOutcomeContributor` makes the endpoint beans available without `management.endpoints.web.exposure` (see "In-Process Actuator Invocation")
6. **Caffeine for storage**: Bounded memory with automatic eviction
7. **Shadow DOM**: Toolbar cannot interfere with host application
8. **Lowest-priority defaults**: Apps can always override peekaboot settings
9. **Shared frontend design system, one override point**: The dashboard, toolbar, and
   trace-detail overlay consume the same `tokens.css`/`base.css`/`components.css` (see
   "peekaboot-frontend" above and `peekaboot-frontend/README.md`) instead of each
   maintaining its own palette and primitives. `tokens.css` is the single documented
   place a consuming application overrides to re-theme every surface.

## Auto-Configuration Ordering

`DevToolbarAutoConfiguration` requires specific ordering to ensure the `Tracer` bean exists:

```java
@AutoConfiguration(
    after = PeekabootAutoConfiguration.class,
    afterName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration"
)
```

The `afterName` attribute (string-based) is used instead of class reference to avoid compile-time dependency on the tracing autoconfigure module.
## Known defects

Recorded here so a maintainer sees them without having to rediscover them. Each entry
names the class at fault, the remedy, and the site page that carries the user-visible
symptom.

1. **No secret masking by default on the Environment/Config tabs.** Spring Boot 4.1
   registers no default `SanitizingFunction`. Combined with Peekaboot's own
   `show-values: always` for `env` and `configprops` (set in `peekaboot-defaults.yml`,
   loaded by `PeekabootDefaultsEnvironmentPostProcessor`), the Environment and Config
   tabs render every property value &mdash; including secrets &mdash; verbatim on an
   unauthenticated endpoint. Remedy: ship a default `SanitizingFunction` bean, or drop
   the `show-values: always` defaults. Symptom documented at
   [peekaboot.org/docs/security](https://peekaboot.org/docs/security/#masking).
2. **`PeekabootAutoConfiguration` is missing the servlet guard.** Unlike
   `DevToolbarAutoConfiguration` and `TracingInterceptorAutoConfiguration`,
   `PeekabootAutoConfiguration` carries no `@ConditionalOnWebApplication(Type.SERVLET)`,
   yet it component-scans the servlet-only `PeekabootWebConfig`
   (a `WebMvcConfigurer`). A non-servlet application (WebFlux, or no web application at
   all) should fail fast at startup rather than silently staying inactive. Remedy: add
   the annotation. Symptom documented at
   [peekaboot.org/docs/requirements](https://peekaboot.org/docs/requirements/) and
   [peekaboot.org/docs/how-activation-works](https://peekaboot.org/docs/how-activation-works/).
3. **`max-spans-per-trace` truncates before deduplication.**
   `PeekabootTracingProperties.maxSpansPerTrace` (default 100) caps span storage in
   `InMemoryTraceStore` at write time, before `SpanDeduplicator` ever runs. A query-heavy
   endpoint can lose the spans that would have triggered `HIGH_QUERY_COUNT` before
   dedup gets a chance to collapse the duplicate JDBC/`datasource-proxy` span pairs that,
   left uncollapsed, roughly double stored span volume &mdash; so the cap bites about
   twice as early as its number suggests. Remedy: run deduplication (or at least count
   distinct queries) before truncating, not after. Symptom documented at
   [peekaboot.org/docs/tracing](https://peekaboot.org/docs/tracing/#span-deduplication).
4. **Scheduled-job classification substring-matches the span name.**
   `TraceTreeMapper.detectRootActionType()` classifies `SCHEDULED_JOB` only when the root
   span's name contains "schedule", "cron", "timer" or "job". Whether a `@Scheduled`
   method is recognised then depends on its bean/method name and on whether Spring's own
   scheduled-task observation wraps it as the root span: `task
   scheduler.fixedDelay` matches on "schedul", while `task
   orderReconciler.reconcileOrders` matches nothing and classifies `INTERNAL`. Remedy:
   key classification on span tags (or Spring's scheduled-task observation type) instead
   of a name substring. Symptom documented at
   [peekaboot.org/docs/concepts](https://peekaboot.org/docs/concepts/#root-action-type).
