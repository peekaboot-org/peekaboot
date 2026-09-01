# Testing Conventions

## Structure
- JUnit 5 + AssertJ everywhere; no JUnit `assertEquals` in new code.
- One behavior per test. Helpers/fixtures either all above or all below the tests, never
  interleaved with them.
- Deterministic time: fixed `Instant.parse(...)` values, never bare `Instant.now()`
  when the assertion depends on ordering or duration.
- Micrometer gauges in tests: never `registry.gauge(name, obj)` with the result discarded —
  the registry holds `obj` weakly and samples become NaN after a GC; use
  `Gauge.builder(name, supplier)` or keep the returned object in a field.

## Real collaborators over mocks
Mock only when the real dependency is expensive, non-deterministic, or external
(servlet machinery, Micrometer `Tracer`/`Span`, Spring container callbacks, JDBC
`DataSource`, or an in-module class whose real construction needs a live container).
Cheap in-module classes (`InMemoryTraceStore`, `QueryExtractor`, `ToolbarDataProvider`,
mappers) are used for real. Spring/servlet machinery (`MockMvc`, mock requests) is fine.

Exception: controller tests that stub a service and assert `isSameAs` pass-through
(sentinel-identity delegation) are a legitimate use of a stub even when the service
itself would otherwise be cheap to construct — a real service instance offers no
sentinel object for `isSameAs` to check, so the assertion structurally requires a
stub. The "prefer real collaborators" rule targets cheap in-module data/logic
collaborators being asked to compute something, not this pass-through shape.

## Pristine output
Test output must be silent: no ERROR lines, no stack traces, no unexplained WARN.
- Browser-side errors are asserted where a test's subject is the JavaScript itself:
  `page.onPageError` collects into a list the test then asserts on. Classes whose subject
  fails invisibly (the charts, the lifecycle table) opt into
  `PlaywrightTestBase.captureBrowserSignals()`, which prints every console message, page
  error, failed request and error response at teardown. There is no shared listener that
  filters, and no allow-list — the Chromium engine has no incompatibilities of its own to
  excuse.
- Tests that trigger error paths capture the log event (logback `ListAppender`) and assert
  it instead of letting it print. `peekaboot-backend` shares one helper for this,
  `org.peekaboot.backend.testsupport.LogCapture`; it is test-scoped to that module, so
  `peekaboot-spring-boot-autoconfigure`'s tests attach their own `ListAppender`.
- `peekaboot-testing-app`'s `logback-test.xml` sets `org.apache.catalina.core.ContainerBase`
  to `OFF`: `OrderController`'s deliberately failing `/boom` endpoint escapes as an unhandled
  exception, which embedded Tomcat logs as a full stack trace under a per-JVM
  instance-numbered logger name (`Tomcat`, `Tomcat-1`, ...) that Logback — which has no
  wildcard matching — can't target directly; `ContainerBase` is the narrowest static ancestor
  that covers every run. Accepted because the module configures no clustering and no custom
  realm (the only other things that logger would silence) and the application's own error
  logging is unaffected — a real application failure still prints and is still asserted.
- Accepted, unavoidable noise:
  - `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader
    classes because bootstrap classpath has been appended` — a lowercase-`warning`
    JVM CDS notice printed once per forked JVM, caused by Mockito's inline
    mock-maker calling `Instrumentation.appendToBootstrapClassLoaderSearch` during
    its javaagent bootstrap. No further config knob exists for it.
  - `sun.misc.Unsafe` deprecation `WARNING:` block (`peekaboot-spring-boot-autoconfigure`
    module only) — fired by protobuf's reflective `Unsafe` access, a transitive
    OTel/gRPC dependency. Third-party, not application or test code; a real fix
    would mean a protobuf/gRPC version bump, out of scope for test cleanup.
  - `ERROR ... o.p.testingapp.Scheduler : fixedRate failed` from `Scheduler.fixedRate()`,
    and the `IllegalStateException: fixedDelay failed` from `Scheduler.fixedDelay()` (logged
    by Spring's `TaskUtils$LoggingErrorHandler` as `ERROR ... Unexpected error occurred in
    scheduled task`, with the full stack trace) — deliberate demo signal in
    `peekaboot-testing-app`, giving the dashboard's Errors bucket a scheduled-job failure to
    show.
  - `WARN ... o.p.testingapp.order.OrderReconciler : order <reference> is still PLACED and
    has not been acknowledged`, one line per stale order — deliberate demo signal giving the
    Logs tab WARN content on a non-HTTP (`SCHEDULED_JOB`) trace.
  - `ERROR ... o.p.t.controller.OrderController : order reconciliation gateway is
    unreachable` — `OrderController`'s deliberately failing `/boom` endpoint, exercised to
    populate the Errors bucket and the toolbar's error styling.
  - `ERROR ... o.p.t.controller.PersonController : An error occurred while trying to find
    all persons` — `PersonController`'s deliberate error path (`/?error=true`), same purpose.
  - `WARN ... o.f.c.internal.database.base.Database : Using H2 <version> which is newer than
    the version Flyway has been verified with. The latest verified version of H2 is
    <version>.` — a Flyway/H2 version-compatibility `WARN`, printed once per Spring context
    start.

## Known flakes
- `TraceOverlayIT` — a Playwright `TargetClosedError` was seen once from `@AfterEach`'s
  `page.context().close()`, in `closeButtonDismissesTheOverlayOnTheErrorPath`. Root-caused and
  fixed: the collapsed toolbar's own fetch ladder (`toolbar.js`) keeps polling
  `/api/traces/{id}/insights` for up to 4.75s after page load, independent of any one test's
  lifetime. That test routes the same endpoint (`page.route("**/api/traces/*/insights", route ->
  route.abort())`) and never unroutes it, so a scheduled poll can still fire while teardown's
  `context().close()` is mid-flight, and Playwright's client tries to sync interception patterns
  against a target that is already closing. `PlaywrightTestBase.closePage()` navigates to
  `about:blank` first, which stops the pollers before the close, and catches
  `TargetClosedError` around `context().close()` as insurance against the same race.
  Characterised by running `mvn -pl
  peekaboot-testing-app verify -Dit.test=TraceOverlayIT` repeatedly before the fix (reproduced once
  in 7 runs) and after (0 failures across 8 full-class reruns plus 6 focused reruns of the
  previously-failing method).

  Three tests share this route-and-never-unroute shape, which is why the fix lives in
  `PlaywrightTestBase` rather than per-test `unroute()` calls:
  `TraceOverlayIT.closeButtonDismissesTheOverlayOnTheErrorPath`;
  `ToolbarIT.toolbarShowsPendingWhenTheTraceRequestFails` (identical pattern, and it
  deliberately waits out all four fetch-ladder attempts before teardown runs); and
  `ToolbarIT.openOverlayImportFailureIsCaughtAndLeavesTheBarUsable`, which routes
  `trace-detail.js` and must *not* unroute — its Javadoc explains the browser's module map
  caches the failed dynamic import, so a real reopen would require more than removing the route.

## Isolation in shared Spring contexts
`@SpringBootTest` classes sharing mutable singletons (e.g. `TraceStore`) reset
that state first thing in `@BeforeEach` (`traceStore.clear()`), so tests assert
exact counts, never defensive `contains`. Since `*IT` classes run concurrently,
a class that clears shared state this way MUST hold the corresponding
`@ResourceLock(..., mode = READ_WRITE)`, and every class pinning its own data in
that same store holds the `READ` side — `DashboardTraceViewIT` and `DevToolbarIT`
are the pattern. A class on its own context
configuration (its own app) needs no lock.

The database needs no such discipline: `application-test.yml` and `application-security.yml`
set no `spring.datasource.url`, so Boot's `generate-unique-name` default gives every context its
own `jdbc:h2:mem:<uuid>` (H2 keys in-memory databases by name per JVM — a shared fixed name
would let each lazily booted context's `ddl-auto: create-drop` wipe the tables under whichever
test is mid-flight). Only `FlywayTabIT` names its database, because it needs `MODE=PostgreSQL`
on the URL. The `TraceStore` is the only state the shared context's classes contend for.

## Spring Security on the testing-app classpath

`peekaboot-testing-app` carries `spring-boot-starter-security` in test scope for two tests
only: `SecuredPeekabootIT` and `SecuredDashboardIT`, which prove the
`SecurityFilterChain` the website's security page publishes. Test scope keeps it out of the
repackaged jar and out of `spring-boot:run`, so the sample app itself still starts
unsecured.

Left auto-configured, it would put `anyRequest().authenticated()` in front of every
`@SpringBootTest` in the module. So `application-test.yml` and `application-screenshots.yml`
both exclude the servlet security auto-configuration (`UserDetailsServiceAutoConfiguration`,
`ServletWebSecurityAutoConfiguration`, `ManagementWebSecurityAutoConfiguration`) &mdash;
two files rather than one because `ScreenshotCapture` runs with `inheritProfiles = false`
and never sees the first. The two security tests run under their own `security` profile
instead, which excludes nothing, so neither has to undo a module-wide setting.

One gotcha: an inlined `@SpringBootTest(properties = "spring.autoconfigure.exclude=...")`
*replaces* the profile's value for that key rather than merging with it, so a context that
sets its own exclusion list has to repeat the security exclusions too.
`PeekabootActuatorServiceIT` is the one that does; anything added to the profile's list
belongs in its list as well. Symptom when it's missed: Boot logs `Using generated security
password` for that context.

The example config itself, `PeekabootSecurityConfig`, lives in `org.peekaboot.example.security`
&mdash; outside `TestingApp`'s component-scan root on purpose, since a stray
`SecurityFilterChain` bean would fail the context of every test that excluded the
auto-configuration. The two tests name it in `@SpringBootTest(classes = ...)`.

## Running
- Fast gate (unit tests + Error Prone only): `mvn test` (root). Single unit-test class:
  `mvn -pl <module> test -Dtest=<Class>` — never combine `-am` with `-Dtest`.
- Everything that boots an application lives in `*IT` classes (failsafe, `integration-test`
  phase) and only runs under `verify`; `peekaboot-testing-app` runs them as concurrent
  classes in one JVM, 2 worker threads with a Chromium each
  (`-Dpeekaboot.it.threads=1` to serialize while debugging). A test that asserts on
  app-global state shared with other classes must either pin to its own traceId or take
  a `@ResourceLock` (see `DashboardTraceViewIT` for the store-clearing WRITE side).
  Single class: `mvn -pl <module> verify -Dit.test=<Class>`.
- Full reactor — unit tests, integration tests, the five static-analysis gates and the
  coverage floor: `mvn clean verify`.
- Write-path benchmark, excluded from the default suite:
  `mvn -pl peekaboot-backend test -Dtest=TraceWritePathBenchmark`
- Regenerate the website's screenshots (needs Docker — real PostgreSQL and Flyway):

  ```bash
  mvn -pl peekaboot-testing-app test -Dtest=ScreenshotCapture \
      -Dpeekaboot.screenshots.out=/absolute/path/to/peekaboot-org.github.io/assets/img/screenshots
  ```

  See `peekaboot-testing-app/README.md`'s screenshot section — `docs/images/dashboard.png`
  is a byte-identical copy of one of those images and is regenerated with them. What the
  capture tool reaches &mdash; and what it deliberately does not &mdash; is described in
  [`IMPROVEMENTS.md`](IMPROVEMENTS.md).

## Counting tests
Surefire's per-class `.txt` summaries report `Tests run: 0` for classes using `@Nested`, so
summing them under-reports. Count from the XML instead — see
[`IMPROVEMENTS.md`](IMPROVEMENTS.md) §2.3 for the command and why annotation-counting is also
wrong.
