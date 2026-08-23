<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="peekaboot-frontend/src/main/resources/static/peekaboot/ui/assets/logo-mark-dark.png">
    <img src="peekaboot-frontend/src/main/resources/static/peekaboot/ui/assets/logo-mark.png" width="64" height="64" alt="Peekaboot">
  </picture>
</p>

# Peekaboot

Embedded application introspection for Spring Boot — health, config, migrations, logs,
schedules, metrics and traces in one dashboard, with no external infrastructure.

## Quick start

**Maven**

```xml
<dependency>
    <groupId>org.peekaboot</groupId>
    <artifactId>peekaboot-spring-boot-starter</artifactId>
    <version>0.0.4-SNAPSHOT</version>
</dependency>
```

**Gradle**

```groovy
implementation("org.peekaboot:peekaboot-spring-boot-starter:0.0.4-SNAPSHOT")
```

Run your app the way you already do. Peekaboot detects local development and turns itself
on — open the dashboard at `http://localhost:8080/peekaboot/`.

![The Peekaboot dashboard](docs/images/dashboard.png)

## What you get

- App-insights dashboard: health, environment, config, Flyway, loggers and scheduled tasks,
  read from Actuator in-process, plus metrics read directly from Micrometer's
  `MeterRegistry` — nothing exposed under `/actuator/**`
- In-memory request tracing via Micrometer/OpenTelemetry, no collector to run
- A dev toolbar (`peekaboot.dev-toolbar: true`) that also correlates logs to each trace and
  captures full request/response detail — neither is captured without it
- Zero configuration: on automatically in local development, off everywhere else

## Documentation

Full docs — configuration reference, security guidance, the dashboard tour, and more — live
at **[peekaboot.org](https://peekaboot.org)**.

| Page | |
| --- | --- |
| [Quick start](https://peekaboot.org/docs/quick-start/) | One dependency, no configuration, a dashboard on your next run |
| [Configuration](https://peekaboot.org/docs/configuration/) | Every `peekaboot.*` property, grouped by prefix, with its default |
| [Security](https://peekaboot.org/docs/security/) | What Peekaboot exposes when it's on, and how to lock it down |
| [The dashboard](https://peekaboot.org/docs/dashboard/) | A tour of every tab |

## Working on Peekaboot

```
peekaboot/
├── peekaboot-backend/                    # Core logic and APIs
├── peekaboot-frontend/                   # Static web resources
├── peekaboot-spring-boot-autoconfigure/  # Auto-configuration
├── peekaboot-spring-boot-starter/        # Dependency aggregator
└── peekaboot-testing-app/                # Sample app + UI tests
```

```bash
mvn clean install   # full build, all modules
mvn test             # test suite only

cd peekaboot-testing-app && mvn spring-boot:run   # run the sample app
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module/event/data flow,
[`docs/TESTING.md`](docs/TESTING.md) for testing conventions,
[`docs/GLOSSARY.md`](docs/GLOSSARY.md) for domain terms,
[`peekaboot-frontend/README.md`](peekaboot-frontend/README.md) for the frontend's design
system, and [`peekaboot-testing-app/README.md`](peekaboot-testing-app/README.md) for the
sample app's demo scenarios and the screenshot-capture command.

## Requirements

- Java 25+
- Spring Boot 4.1 (built and tested against)

## License

Apache License 2.0
