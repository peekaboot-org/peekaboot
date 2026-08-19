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
`mvn test` needs neither Docker nor network access — except for the Playwright browser
described below.

```bash
mvn -pl peekaboot-testing-app -am test
```

This module is not published to Maven Central (`maven.deploy.skip`).

### Playwright browser (UI tests under `ui/`)

The `PlaywrightTestBase`-derived tests drive a real headless Chromium instance. The first
time `Playwright.create()` runs on a machine, the Playwright Java driver automatically
downloads the Chromium build it needs into `~/.cache/ms-playwright` — no manual step is
required as long as the machine has network access. That's what happened when this harness
was first verified: `mvn -pl peekaboot-testing-app -am test -Dtest=DashboardShellTest` downloaded
Chromium on its own and both tests passed.

If the automatic download doesn't happen (e.g. `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` is set, or
the cache was wiped), install the browser explicitly with Playwright's own CLI, run through
the `exec-maven-plugin`:

```bash
mvn -pl peekaboot-testing-app exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.classpathScope=test \
  -Dexec.args="install chromium"
```

`-Dexec.classpathScope=test` is required — the `playwright` dependency is test-scoped, and
without this flag `exec:java` can't find `com.microsoft.playwright.CLI` on its default
(compile/runtime) classpath.

Do **not** add `--with-deps` to `exec.args` unless you can `sudo` without a password: it
tries to `apt-get install` OS-level shared libraries for every Playwright-supported browser
and fails outright in a sandboxed/non-root shell. Plain `install chromium` only downloads the
browser binary and does not need root. On a fresh machine missing an OS library
(`libwoff2dec.so.1.0.2` was the one observed here), Playwright prints a non-fatal "Host
validation warning" at the start of the test run; it does not affect headless Chromium runs.

CI (`.github/workflows/build-on-push.yml`) caches `~/.cache/ms-playwright` keyed on the
`pom.xml` files so the download only happens once per dependency change. GitHub-hosted
Ubuntu runners have passwordless `sudo`, so `--with-deps` would be available there if a
missing OS library ever turns a "Host validation warning" into an actual failure — that has
not been necessary so far.
