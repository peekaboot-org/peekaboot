# Peekaboot Testing App

A small Spring Boot application that uses `peekaboot-spring-boot-starter` exactly as a
consumer would. It is where you see Peekaboot working, and it hosts the project's
Playwright UI suite and the screenshot tool that feeds the website.

## Running the app (`src/main`)

```bash
cd peekaboot-testing-app && mvn spring-boot:run
```

Starts on <http://localhost:8083> with the dashboard at
<http://localhost:8083/peekaboot/> and the dev toolbar injected into every page. The
datasource is a PostgreSQL container started automatically by Spring Boot's Docker Compose
support, so Docker needs to be running. Flyway migrations and a `@Scheduled` job give the
Flyway and Scheduled Tasks tabs real data to show.

### Demo endpoints

A small order domain (`CustomerOrder`/`OrderLine`, seeded by `V3`/`V4`) exists purely to
give Peekaboot's trace view something worth looking at.

| Endpoint | What it demonstrates |
| --- | --- |
| `GET /orders` | A deliberate N+1: one query for all orders, then three more per order, plus an outbound HTTP call per page load. Trips the high-trace-query-count warning in the Traces tab. |
| `GET /api/orders/{id}/report` | Three artificially slow, individually `@Observed` stages (`load-lines`, `price-lines`, `apply-discounts`), so the Slow bucket has a trace whose span tree shows where the time went. |
| `POST /api/orders` | Places a new order. Shows up as its own `HTTP_REQUEST`-classified trace, distinct from a page load. |
| `GET /` and `GET /persons` | The person lookup behind both pages is `@Observed`, so it is a span of its own rather than an anonymous gap above the JDBC spans it triggers, and it logs its result inside that span. Add `?error=true` to the index page and the handler logs an `ERROR` of its own. That gives one trace whose logs sit on two different spans, which is what the trace overlay's per-span "N logs" navigation is there to show. |
| `GET /api/person/all` | The same `@Observed` lookup over JSON. A `@RestController` renders no view, so the span tree has no view-render span under the handler. |
| `GET /api/person/{id}` | A single-row lookup that is *not* `@Observed`. Its JDBC span hangs straight off the handler span, which is what the observed lookup above avoids. |
| `GET /boom` | Always throws. Gives the Errors bucket, the error badge and the toolbar's error styling something real to render. |
| `OrderReconciler.reconcileOrders()` (`@Scheduled`, every 2 minutes) | Logs a `WARN` per still-`PLACED` order. Fired by Spring's scheduler, its scheduled-task observation wraps the call and becomes the root span (named `task orderReconciler.reconcileOrders`), carrying the `code.function`/`code.namespace` tags that classify the trace `SCHEDULED_JOB`. A direct call, as some integration tests make, skips that observation: the method's own `@Observed` span becomes the root instead and classifies `INTERNAL`. |

`OrderTraceCaptureIT` asserts what Peekaboot actually captured from these endpoints, not
just what they returned.

## The automated UI suite (`src/test`)

The Playwright tests and every test that boots a Spring context live here, all in `*IT`
classes, so they run under failsafe at `verify`. `mvn test` runs nothing in this module.
They activate the `test` profile, which swaps PostgreSQL for in-memory H2 and disables
Docker Compose, so the suite needs neither Docker nor network access. The one exception
is the Playwright browser described below.

```bash
mvn -pl peekaboot-testing-app -am verify                       # the whole suite
mvn -pl peekaboot-testing-app verify -Dit.test=<Class>         # one *IT class
```

This module is not published to Maven Central (`maven.deploy.skip`).

### Playwright browser (UI tests under `ui/`)

The `PlaywrightTestBase`-derived tests drive a real headless Chromium instance. The first
time `Playwright.create()` runs on a machine, the Playwright Java driver downloads the
Chromium build it needs into `~/.cache/ms-playwright`. No manual step is required as long
as the machine has network access. A first
`mvn -pl peekaboot-testing-app verify -Dit.test=DashboardShellIT` on a clean machine
downloads Chromium on its own and then runs the tests.

If the automatic download does not happen (`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` is set, say,
or the cache was wiped), install the browser explicitly with Playwright's own CLI, run
through the `exec-maven-plugin`:

```bash
mvn -pl peekaboot-testing-app exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.classpathScope=test \
  -Dexec.args="install chromium"
```

`-Dexec.classpathScope=test` is required. The `playwright` dependency is test-scoped, and
without this flag `exec:java` cannot find `com.microsoft.playwright.CLI` on its default
(compile/runtime) classpath.

Do not add `--with-deps` to `exec.args` unless you can `sudo` without a password. It tries
to `apt-get install` OS-level shared libraries for every Playwright-supported browser and
fails outright in a sandboxed or non-root shell. Plain `install chromium` only downloads
the browser binary and does not need root. On a fresh machine missing an OS library
(`libwoff2dec.so.1.0.2` was the one observed here), Playwright prints a non-fatal "Host
validation warning" at the start of the test run. It does not affect headless Chromium
runs.

CI (`.github/workflows/build-on-push.yml`) caches `~/.cache/ms-playwright` keyed on the
`playwright.version` property in this module's pom, so the download only happens when
Playwright, and with it Chromium, changes. GitHub-hosted Ubuntu runners have passwordless
`sudo`, so `--with-deps` would be available there if a missing OS library ever turned a
"Host validation warning" into an actual failure. That has not been necessary so far.

## Screenshot capture (`ScreenshotCapture`)

`src/test/java/.../ui/ScreenshotCapture.java` photographs every dashboard tab, the
trace-detail overlay and the dev toolbar, in both light and dark themes, for the
peekaboot.org website. It is a tool, not a test, and is deliberately not named `*Test`, so
surefire's default includes never pick it up and a normal `mvn test` never runs it or
touches Docker. It is Maven-only. The Gradle build has no task that includes it, because
Gradle's `--tests` filter cannot widen the `*Test`/`*IT` includes the way surefire's
`-Dtest` does.

```bash
mvn -pl peekaboot-testing-app test -Dtest=ScreenshotCapture \
    -Dpeekaboot.screenshots.out=/absolute/path/to/output/dir
```

It runs under the `screenshots` profile (`application-screenshots.yml`), which points at
the real PostgreSQL container from `compose.yml` with Flyway on, so the Flyway, Config,
Environment and Traces tabs all show genuine content instead of an empty in-memory H2
state. Docker must be running. The profile is declared `inheritProfiles = false` because
`PlaywrightTestBase` carries `@ActiveProfiles("test")`, whose H2 settings would otherwise
win. The Lifecycle tab is photographed against a seeded run history
(`LifecycleHistoryFixture`, shared with `LifecycleTabIT`), since a JUnit launch has storage
off and would otherwise show the application's own run as the tab's only row. Insights is
captured last, once its ring holds a dozen level-0 samples, about two minutes at the
default 10s interval, so a chart shows a real line instead of a first point. Expect a run
to take a few minutes.

The output directory is required (the tool refuses to guess) and is created if missing. A
successful run writes 30 PNGs, each surface in light and dark. That is one per dashboard
tab (10, Insights and Lifecycle included), the trace-detail overlay's Spans and Queries
views (2), the collapsed toolbar (1), and a revealed counterpart of the Environment and
Config tab shots (2). The revealed pair shows the `spring.datasource.password` fixture
after the "Show secrets" control is clicked. See
`ScreenshotCapture.MASKED_GROUP_HEADER_SELECTOR`'s doc comment for exactly which group that
is and is not, and why.

Widening that scope is a security decision, not a cosmetic one. The one revealed value is
safe to publish because it is a placeholder already plaintext in this repository
(`compose.yml`, `application-screenshots.yml`). That reasoning covers no other group, and
never `systemEnvironment` or `systemProperties`, which are read from whatever machine runs
the capture and can carry real usernames, paths, hostnames or credentials. Before revealing
another group, confirm that every value in it, not only the one being added, originates in
a file already committed here. When in doubt, don't click it.

The Overview tab has the same problem without a click. It renders the OS user with its uid
and gid, the pid, the whole parent-process chain and every non-local IP address with its
reverse-resolved hostname, all read from the capture machine. `ScreenshotIdentityScrub`
replaces those rows with placeholders before the Overview shot, and
`ScreenshotIdentityScrubIT` pins its selectors against the real DOM. The failure it guards
against is silent: a renamed row would leave the capture green and the real value in the
published image. Anything else that starts photographing the host belongs in that scrub,
not in a note asking the next person to remember.

The tool's file names are canonical and follow the dashboard's own tab ids:
`dashboard-<tab>-<theme>.png` (`overview`, `insights`, `lifecycle`, `traces`, `meters`,
`environment`, `flyway`, `loggers`, `config`, `scheduled-tasks`),
`dashboard-<environment|config>-revealed-<theme>.png`, `trace-detail-<theme>.png`,
`trace-detail-queries-<theme>.png` and `toolbar-collapsed-<theme>.png`. The website's
`assets/img/screenshots/` carries these names verbatim. When a tab id changes here, the
site's files and `<img>` references are renamed to match, never the other way around.

`docs/images/dashboard.png` at the product repo root is a byte-identical copy of this
tool's `dashboard-overview-light.png` output, checked into the website repo's
`assets/img/screenshots/`. Regenerate both together when re-running this capture.
