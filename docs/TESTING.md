# Testing Conventions

## Structure
- JUnit 5 + AssertJ everywhere; no JUnit `assertEquals` in new code.
- One behavior per test. Helpers/fixtures either all above or all below the tests, never
  interleaved with them.
- Test names are sentences about behaviour (`aSnapshotDatedInTheFutureIsDeletedUnread`);
  `method_shouldX` is legacy — keep a class internally consistent, use the sentence form for
  new classes.
- No getter/setter round-trips for `@ConfigurationProperties` classes: they prove Java field
  assignment. Assert a default only where it protects something (a safe fallback, parity with
  a constant in main code), or that a bound property reaches the bean that consumes it
  (`PeekabootTracingAutoConfigurationTest.bucketPropertiesReachTheStore`).
- Deterministic time: fixed `Instant.parse(...)` values, never bare `Instant.now()`
  when the assertion depends on ordering or duration.
- Live threads: wait for the condition the assertion needs (Awaitility, e.g. a sample count),
  never a fixed `Thread.sleep`. Where a test would otherwise sit out a production timeout, the
  class offers a package-private seam instead (`LifecycleEventLog(file, loadWait)`).
- Micrometer gauges in tests: never `registry.gauge(name, obj)` with the result discarded —
  the registry holds `obj` weakly and samples become NaN after a GC; use
  `Gauge.builder(name, supplier)` or keep the returned object in a field.

## Fixtures
`peekaboot-backend`'s trace fixtures are built through `org.peekaboot.backend.testsupport`:
`Spans.span(id)` (a `SpanData` with neutral defaults, plus the `jdbcQuery`/`jdbcDuplicate`
presets for the double-instrumented pair), `SpanNodes.node(id)` (an already-mapped
`SpanNode`), `TraceTrees.tree(rootSpan)` (the mapped `TraceTree` around one),
`RequestCompletedEvents.request(traceId)`/`minimal(traceId)`, and
`TraceStores.withDefaults()`/`with(customizer)` (an `InMemoryTraceStore` built the way the
auto-configuration builds it, from `PeekabootTracingProperties`). A test names only what it
asserts on; a new record component is added to the builder once, not to every test class.
The domain records carry no test-only constructors.

## Backend <-> frontend contracts
The frontend is plain ES modules, so a Java enum and its JS mirror drift silently.
`SharedModuleIT`'s `*MirrorTheBackend*` tests pin `ROOT_ACTION_TYPES`, `TASK_TYPES`,
`MIGRATION_STATES`, `ISSUE_TYPES` and `LOG_LEVELS` to `RootActionType`, `TaskType`,
`MigrationState`, `IssueType` and Logback's levels, the keys the frontend reads off
`/api/features` to the `Features` record, and `severity.js`'s `DEFAULT_THRESHOLDS` to the
properties' defaults. A vocabulary that gains a JS mirror gets a row there. The same suite
pins `format.js`'s `formatLongDuration` against `UptimeFormat.humanize` unit by unit, and
drives `dashboard/tabs/insights-store.js` — the browser's mirror of the insights rings —
directly, so the JS with no Java counterpart is covered too.

## Real collaborators over mocks
Mock only when the real dependency is expensive, non-deterministic, or external
(servlet machinery, Micrometer `Tracer`/`Span`, Spring container callbacks, JDBC
`DataSource`, or an in-module class whose real construction needs a live container).
Cheap in-module classes (`InMemoryTraceStore`, `QueryExtractor`, `ToolbarDataProvider`,
mappers) are used for real. Spring/servlet machinery (`MockMvc`, mock requests) is fine.
`InsightsSsePublisherTest` shows how far that reaches: it drives Spring's real
`ResponseBodyEmitterReturnValueHandler` and `StandardServletAsyncWebRequest` over mock
servlet objects, so a container timeout runs the interceptor chain a stubbed emitter would
have skipped.

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
  `PlaywrightTestBase.captureBrowserSignals()`, which collects every console message, page
  error, failed request and error response the test provokes and prints them only when the
  test fails (a `TestWatcher`; a green run prints nothing). Collection stops when teardown
  starts, so the `about:blank` navigation aborting the Insights tab's EventSource is not
  recorded. There is no shared listener that filters, and no allow-list — the Chromium
  engine has no incompatibilities of its own to excuse.
- Tests that trigger error paths capture the log event (logback `ListAppender`) and assert
  it instead of letting it print. The shared helper for this is
  `org.peekaboot.testsupport.LogCapture` from `peekaboot-test-support`, an unpublished
  reactor module both `peekaboot-backend` and `peekaboot-spring-boot-autoconfigure` consume
  at test scope (see [`BUILD.md`](../BUILD.md)).
- `peekaboot-testing-app`'s `logback-test.xml` sets `org.apache.catalina.core.ContainerBase`
  to `OFF`: `OrderController`'s deliberately failing `/boom` endpoint escapes as an unhandled
  exception, which embedded Tomcat logs as a full stack trace under a per-JVM
  instance-numbered logger name (`Tomcat`, `Tomcat-1`, ...) that Logback — which has no
  wildcard matching — can't target directly; `ContainerBase` is the narrowest static ancestor
  that covers every run. Accepted because the module configures no clustering and no custom
  realm (the only other things that logger would silence) and the application's own error
  logging is unaffected — a real application failure still prints and is still asserted.
- The same file raises `org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver`
  to `ERROR`: Playwright teardown aborts in-flight JSON responses, and the resolver WARNs
  `Ignoring exception ... Broken pipe` for each aborted write (Spring-side teardown noise,
  5-20 lines per suite run). A failure the resolver really handles still reaches the client
  as a 4xx/5xx and fails the asserting test, so only that advisory WARN is lost.
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
    has not been acknowledged` — deliberate demo signal giving the Logs tab WARN content
    on a non-HTTP (`SCHEDULED_JOB`) trace. One line per order the context holds, on every
    run: nothing ever moves an order out of `PLACED`, so the count grows with the orders a
    run places.
  - `ERROR ... o.p.t.controller.OrderController : order reconciliation gateway is
    unreachable` — `OrderController`'s deliberately failing `/boom` endpoint, exercised to
    populate the Errors bucket and the toolbar's error styling.
  - `ERROR ... o.p.t.controller.PersonController : An error occurred while trying to find
    all persons` — `PersonController`'s deliberate error path (`/?error=true`), same purpose.
  - `WARN ... o.f.c.internal.database.base.Database : Using H2 <version> which is newer than
    the version Flyway has been verified with. The latest verified version of H2 is
    <version>.` — a Flyway/H2 version-compatibility `WARN`, printed by `FlywayTabIT`'s
    context, the only test in the default suite that enables Flyway (both shared profiles
    set `spring.flyway.enabled: false`).

## Teardown and the toolbar's fetch ladder
`PlaywrightTestBase.closePage()` navigates to `about:blank` before `context().close()`, and
catches `TargetClosedError` around the close as insurance. The reason: the collapsed
toolbar's fetch ladder (`toolbar.js`) polls `/api/traces/{id}/insights` for up to 4.75s
after page load, independent of any one test's lifetime, and three tests route that
traffic and never unroute it — `TraceOverlayIT.closeButtonDismissesTheOverlayOnTheErrorPath`;
`ToolbarIT.toolbarShowsPendingWhenTheTraceRequestFails` (which deliberately waits out all
four ladder attempts before teardown); and
`ToolbarIT.openOverlayImportFailureIsCaughtAndLeavesTheBarUsable`, which routes
`trace-detail.js` and must *not* unroute, because the browser's module map caches the
failed dynamic import. A scheduled poll firing while the close is mid-flight makes
Playwright's client sync interception patterns against a target that is already closing.
The `about:blank` navigation stops the pollers first; per-test `unroute()` calls would not
close the race, since `unroute` is itself an interception update over the same wire. The
history of the flake this rule closed is in [`IMPROVEMENTS.md`](IMPROVEMENTS.md) §5.6.

## Isolation in shared Spring contexts
`@SpringBootTest` classes sharing mutable singletons (e.g. `TraceStore`) reset
that state first thing in `@BeforeEach` (`traceStore.clear()`), so tests assert
exact counts, never defensive `contains`. Since `*IT` classes run concurrently,
a class that clears shared state this way MUST hold the corresponding
`@ResourceLock(..., mode = READ_WRITE)`, and every class pinning its own data in
that same store holds the `READ` side — `DashboardTraceViewIT` and `DevToolbarIT`
are the pattern. A class on its own context
configuration (its own app) needs no lock.

Pinning to a traceId does not mean searching the store for it: a JSON endpoint answers
with `Server-Timing: trace;desc="00-<traceId>-..."` for every captured request, which
`OrderTraceCaptureIT` matches with a pattern to name the trace its own call produced.

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
  phase) and only runs under `verify`. The one exception is
  `PeekabootDefaultsRegistrationTest`, a `*Test` that runs a real non-web
  `SpringApplication` on purpose, so the fast gate covers `spring.factories` registration
  and default-property precedence; it boots no server and costs a fraction of a second.
  `peekaboot-testing-app` runs its `*IT`s as concurrent classes in one JVM, 2 worker
  threads with a Chromium each (`-Dpeekaboot.it.threads=1` to serialize while debugging). A test that asserts on
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
Surefire's per-class `.txt` summaries report `Tests run: 0` for classes using `@Nested`
(`PeekabootControllerTest`, `MaskingEngineTest`), so summing them under-reports. Counting
`@Test` annotations is also wrong: it misses `@ParameterizedTest`, and `MaskingEngineTest`
alone has 7 that expand to 107 invocations. Count from the XML, or from the reactor
summary, never from the `.txt` files:

```bash
for f in <module>/target/surefire-reports/TEST-*.xml; do
  grep -m1 -o 'tests="[0-9]*"' "$f" | grep -o '[0-9]*'
done | paste -sd+ | bc
```
