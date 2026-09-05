<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="peekaboot-frontend/src/main/resources/META-INF/peekaboot/ui/assets/logo-mark-dark.png">
    <img src="peekaboot-frontend/src/main/resources/META-INF/peekaboot/ui/assets/logo-mark.png" width="64" height="64" alt="Peekaboot">
  </picture>
</p>

# Peekaboot

Embedded application introspection for Spring Boot. Health, config, migrations, logs,
schedules, metrics and traces in one dashboard, with no external infrastructure.

## Quick start

**Maven**

```xml
<dependency>
    <groupId>org.peekaboot</groupId>
    <artifactId>peekaboot-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Gradle**

```groovy
implementation("org.peekaboot:peekaboot-spring-boot-starter:0.1.0")
```

Run your app the way you already do. Peekaboot detects local development and turns
itself on. Open the dashboard at `http://localhost:8080/peekaboot/`. Local development
means an IDE run, `mvn spring-boot:run` or `gradle bootRun` on your own machine; a
packaged jar, a container, a test, an AOT build or a native image counts as not local.
Set `peekaboot.enabled=true|false` (and `peekaboot.dev-toolbar` for the toolbar alone)
to override the detection in either direction.

![The Peekaboot dashboard](docs/images/dashboard.png)

## What you get

- A dev toolbar docked to every page, with request status, duration, query count and a
  link to the full trace.
- An app-insights dashboard for health, environment, config, Flyway, loggers, scheduled
  tasks and metrics, read in-process with nothing exposed under `/actuator/**`.
- In-memory request tracing via Micrometer and OpenTelemetry, with no collector to run.
- Charts that mark every application start and stop, with metric history surviving a
  restart.
- Zero configuration, on in local development and off everywhere else.

## Documentation

Full docs live at [www.peekaboot.org](https://www.peekaboot.org).

| Page | |
| --- | --- |
| [Quick start](https://www.peekaboot.org/docs/quick-start/) | One dependency, no configuration, the toolbar on your next run |
| [Configuration](https://www.peekaboot.org/docs/configuration/) | Every `peekaboot.*` property, grouped by prefix, with its default |
| [Security](https://www.peekaboot.org/docs/security/) | What Peekaboot exposes when it's on, and how to lock it down |
| [The dashboard](https://www.peekaboot.org/docs/dashboard/) | A tour of every tab |

## Working on Peekaboot

```
peekaboot/
├── peekaboot-backend/                    # Core logic and APIs
├── peekaboot-test-support/               # Shared test helpers (never published)
├── peekaboot-frontend/                   # Static web resources
├── peekaboot-spring-boot-autoconfigure/  # Auto-configuration
├── peekaboot-spring-boot-starter/        # Dependency aggregator
├── peekaboot-testing-app/                # Sample app + UI tests
└── peekaboot-coverage/                   # JaCoCo aggregate report + coverage floor
```

```bash
mvn clean install   # full build, all modules
mvn test            # unit tests only (~1 min); integration tests need `mvn verify`

cd peekaboot-testing-app && mvn spring-boot:run   # run the sample app; needs a prior
                                                  # `mvn install` and a running Docker
```

`mvn verify` also enforces nine gates: five static-analysis tools (Spotless, Error
Prone, SpotBugs, Checkstyle, PMD), three dependency and output checks, and the
reactor-wide JaCoCo coverage floor in `peekaboot-coverage`. Local builds format your
sources for you; run `mvn spotless:apply` to do it by hand.

Further reading:

- [`BUILD.md`](BUILD.md) for the full build: reactor layout, compiler and gate
  configuration, CI and the release pipeline.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module, event and data flow.
- [`docs/TESTING.md`](docs/TESTING.md) for testing conventions.
- [`docs/GLOSSARY.md`](docs/GLOSSARY.md) for domain terms.
- [`peekaboot-frontend/README.md`](peekaboot-frontend/README.md) for the frontend's
  design system.
- [`peekaboot-testing-app/README.md`](peekaboot-testing-app/README.md) for the sample
  app's demo scenarios and the screenshot-capture command.

## Requirements

- Java 25+. The baseline is the current LTS on purpose. The build compiles with
  `release 25` and has no toolchain fallback, Peekaboot's collectors run on virtual
  threads, and nothing below 25 is built or tested.
- Spring Boot 4.1 (built and tested against).

## License

Apache License 2.0
