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

```xml
<dependency>
    <groupId>org.peekaboot</groupId>
    <artifactId>peekaboot-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Run your app the way you already do. Peekaboot detects a local run, turns itself on and
serves the dashboard at `http://localhost:8080/peekaboot/`. The Gradle coordinate, what
counts as a local run and how to override the detection are in the
[quick start](https://www.peekaboot.org/docs/quick-start/).

![The Peekaboot dashboard](docs/images/dashboard.png)

## What you get

- A dev toolbar docked to every page, with request status, duration, query count and a
  link to the full trace.
- A dashboard for health, environment, config, Flyway, loggers, scheduled tasks and
  metrics, read in-process with nothing exposed under `/actuator/**`.
- In-memory request tracing via Micrometer and OpenTelemetry, with no collector to run.
- Charts that mark every application start and stop, with metric history surviving a
  restart.
- Zero configuration, on for a local run and off everywhere else.

## Documentation

Full docs live at [www.peekaboot.org](https://www.peekaboot.org).

| Page | |
| --- | --- |
| [Quick start](https://www.peekaboot.org/docs/quick-start/) | The dependency, the first run, and what Peekaboot needs from your app |
| [Configuration](https://www.peekaboot.org/docs/configuration/) | Every `peekaboot.*` property, grouped by prefix, with its default |
| [Security](https://www.peekaboot.org/docs/security/) | What Peekaboot exposes when it's on, and how to lock it down |
| [The dashboard](https://www.peekaboot.org/docs/dashboard/) | A tour of every tab |

## Working on Peekaboot

```
peekaboot/
├── peekaboot-test-support/               # Shared test helpers (never published)
├── peekaboot-backend/                    # Core logic and APIs
├── peekaboot-frontend/                   # Static web resources
├── peekaboot-spring-boot-autoconfigure/  # Auto-configuration
├── peekaboot-spring-boot-starter/        # Dependency aggregator
├── peekaboot-testing-app/                # Sample app + UI tests
└── peekaboot-coverage/                   # JaCoCo aggregate report + coverage floor
```

```bash
mvn clean verify    # the real build: every test and every gate
mvn clean install   # the same, plus install into ~/.m2
mvn test            # unit tests only; the *IT classes need `verify`

cd peekaboot-testing-app && mvn spring-boot:run   # the sample app on :8083; needs
                                                  # Docker and a prior `mvn install`
```

The gates `mvn verify` enforces are the static-analysis tools (Spotless, Error Prone,
SpotBugs, Checkstyle, PMD), the dependency and output checks, and the reactor-wide JaCoCo
coverage floor in `peekaboot-coverage`. Local builds format your sources for you; run
`mvn spotless:apply` to do it by hand. A parallel Gradle build (`./gradlew build`) covers
the same modules, tests and gates, but Maven is the system of record and the only thing CI
runs, so a change to one has to be mirrored in the other.

Further reading:

- [`BUILD.md`](BUILD.md) for the reactor, the gates, CI and the release pipeline.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the module structure, the
  auto-configuration wiring, tracing and the data models.
- [`docs/TESTING.md`](docs/TESTING.md) for test structure, fixtures and the
  pristine-output rule.
- [`docs/GLOSSARY.md`](docs/GLOSSARY.md) for domain terms.
- [`peekaboot-frontend/README.md`](peekaboot-frontend/README.md) for the shared frontend
  layers, the design tokens and their pairing rules, and how to add a dashboard tab.
- [`peekaboot-testing-app/README.md`](peekaboot-testing-app/README.md) for the sample
  app, its UI suite and the screenshot-capture command.

## Requirements

- Java 25+, the current LTS. The build compiles with `release 25` and has no toolchain
  fallback, Peekaboot's collectors run on virtual threads, and nothing below 25 is built
  or tested.
- Spring Boot 4.1.

## License

Apache License 2.0
