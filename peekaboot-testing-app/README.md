# Peekaboot Testing App

A small Spring Boot application that uses `peekaboot-spring-boot-starter` exactly as a
consumer would. It serves two purposes.

## `src/main` — launch it and look at it

```bash
cd peekaboot-testing-app && mvn spring-boot:run
```

Starts on <http://localhost:8083> with the dashboard at
<http://localhost:8083/peekaboot/> and the dev toolbar injected into every page. The
datasource is a PostgreSQL container started automatically by Spring Boot's Docker Compose
support, so Docker needs to be running. Flyway migrations and a `@Scheduled` job give the
Flyway and Scheduled Tasks tabs real data to show.

## `src/test` — the automated UI suite

The Playwright tests and every test that boots a Spring context live here. They activate the
`test` profile, which swaps PostgreSQL for in-memory H2 and disables Docker Compose, so
`mvn test` needs neither Docker nor network access.

```bash
mvn -pl peekaboot-testing-app -am test
```

This module is not published to Maven Central (`maven.deploy.skip`).
