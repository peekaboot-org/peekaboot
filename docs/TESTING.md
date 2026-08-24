# Testing Conventions

## Structure
- JUnit 5 + AssertJ everywhere; no JUnit `assertEquals` in new code.
- One behavior per test. Helpers/fixtures at the bottom of the class.
- Deterministic time: fixed `Instant.parse(...)` values, never bare `Instant.now()`
  when the assertion depends on ordering or duration.

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
- HtmlUnit tests install `CollectingJavaScriptErrorListener` (copies in
  `peekaboot-frontend` and `peekaboot-backend` test trees): known engine
  incompatibilities are allow-listed, anything else fails the test.
- Tests that trigger error paths capture the log event (logback `ListAppender`, via
  the shared `org.peekaboot.backend.testsupport.LogCapture` helper in
  `peekaboot-backend`) and assert it instead of letting it print.
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
- `TraceOverlayTest` — a Playwright `TargetClosedError` was seen once from `@AfterEach`'s
  `page.context().close()`, in `closeButtonDismissesTheOverlayOnTheErrorPath`. Root-caused and
  fixed: the collapsed toolbar's own fetch ladder (`toolbar.js`) keeps polling
  `/api/traces/{id}/insights` for up to 4.75s after page load, independent of any one test's
  lifetime. That test routes the same endpoint (`page.route("**/api/traces/*/insights", route ->
  route.abort())`) and never unroutes it, so a scheduled poll can still fire while teardown's
  `context().close()` is mid-flight, and Playwright's client tries to sync interception patterns
  against a target that is already closing. `PlaywrightTestBase.closePage()` now catches
  `TargetClosedError` around `context().close()` as a benign teardown race, the same tolerance it
  already gives `TimeoutError` around the network-idle wait. Characterised by running `mvn -pl
  peekaboot-testing-app test -Dtest=TraceOverlayTest` repeatedly before the fix (reproduced once
  in 7 runs) and after (0 failures across 8 full-class reruns plus 6 focused reruns of the
  previously-failing method).

  Three tests share this route-and-never-unroute shape, which is why the fix lives in
  `PlaywrightTestBase` rather than per-test `unroute()` calls: `TraceOverlayTest:222`;
  `ToolbarTest:207` (identical pattern, and it deliberately waits out all four fetch-ladder
  attempts before teardown runs); and `ToolbarTest:257`, which routes `trace-detail.js` and must
  *not* unroute — its Javadoc explains the browser's module map caches the failed dynamic import,
  so a real reopen would require more than removing the route.

## Isolation in shared Spring contexts
`@SpringBootTest` classes sharing mutable singletons (e.g. `TraceStore`) reset
that state first thing in `@BeforeEach` (`traceStore.clear()`), so tests assert
exact counts, never defensive `contains`.

## Running
- Full suite: `mvn test` (root). Single class: `mvn -pl <module> test -Dtest=<Class>`
  — never combine `-am` with `-Dtest`.
- Full reactor with the five static-analysis gates: `mvn clean verify` (942 tests).
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
