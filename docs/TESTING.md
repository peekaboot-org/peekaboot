# Testing Conventions

## Structure
- JUnit 5 + AssertJ everywhere. No JUnit `assertEquals` in new code.
- One behaviour per test. Helpers and fixtures sit all above or all below the tests, never
  interleaved.
- Test names are sentences about behaviour (`aSnapshotDatedInTheFutureIsDeletedUnread`).
  `method_shouldX` is legacy. Keep a class internally consistent; new classes take the
  sentence form.
- No getter/setter round-trips for `@ConfigurationProperties` classes. They prove Java field
  assignment. Assert a default only where it protects something (a safe fallback, parity with
  a constant in main code), or that a bound property reaches the bean consuming it
  (`PeekabootTracingAutoConfigurationTest.bucketPropertiesReachTheStore`).
- Deterministic time: fixed `Instant.parse(...)` values. Never a bare `Instant.now()` where
  the assertion depends on ordering or duration.
- Live threads: wait for the condition the assertion needs (Awaitility; a sample count, say),
  never a fixed `Thread.sleep`. Where a test would otherwise sit out a production timeout, the
  class offers a package-private seam instead (`LifecycleEventLog(file, loadWait)`).
- Wall clocks and host facts come through seams, never through `System.currentTimeMillis()`
  read inside the class under test. Wall time is a `java.time.Clock`: `LifecycleRuns`,
  `LifecycleEventRecorder`, `DashboardCredentialsResolver`, `CredentialCache`,
  `ApplicationStoppedListener` and `ServerInfo.current(Locale, Clock)` take one, and where a
  timestamp is rendered, the clock's zone is the one it renders in. A `LongSupplier` is kept
  only where a test scripts a sequence of readings: `InsightsCollector` takes one, which also
  dates its captures, and `IntervalBoundary` takes that clock plus a `Sleeper`, so a schedule
  is asserted exactly. `InsightsSnapshotStore` takes an `IntervalBoundary` built on a fixed
  clock, which dates the file and paces the writer alike. `ContainerRuntime`, `NetworkAddress`, `MachineInfo` and `ProcessInfo`
  each read the host through a package-private `Signals` record a test states outright. A test
  that computes its expected value with the same call the production code makes proves
  nothing; it gets a seam instead.
- `Locale.setDefault` is JVM-global. `ApplicationReadyListenerTest` and `ByteFormatTest` set
  it to prove a banner reads the same under any default, restore it in `finally`, and say so in
  their Javadoc. That is safe only because `peekaboot-backend`'s surefire runs its classes one
  at a time on one thread (the pom configures no parallelism). Turn parallel execution on for
  that module and those two tests have to lose the global write first.
- The default uncaught-exception handler is JVM-global the same way.
  `InsightsSsePublisherTest`'s `UncaughtExceptions` installs one to prove nothing escapes the SSE
  sender thread, and restores the previous handler on close. It keeps only what a thread named
  `Subscriber.SENDER_THREAD` threw, since every other thread alive in the JVM reaches the same
  handler and would otherwise fail those assertions. Its awaits pass
  `dontCatchUncaughtExceptions()`, since Awaitility otherwise takes the handler over for the
  length of the wait and rethrows into the test thread.
- Micrometer gauges: never `registry.gauge(name, obj)` with the result discarded. The registry
  holds `obj` weakly and samples turn NaN after a GC. Use `Gauge.builder(name, supplier)`, or
  keep the returned object in a field.
- Timing a test does not own: a fixed delay raced against the page load loses on a busy
  machine, because the load stretches without bound. `ToolbarLateSpanIT` holds the toolbar's
  first read until the root span is stored and releases its late span only after that read
  has rendered. It relies on nothing but the fetch ladder (`toolbar.js`, documented in
  `peekaboot-frontend/README.md`) reading again after its first attempt.

## Suites

Two kinds, split by lifecycle:

- `*Test`: plain unit tests, run by surefire at `test`. The one deliberate exception is
  `PeekabootDefaultsRegistrationTest`, which runs a real non-web `SpringApplication`. It boots
  no server, and what it proves (`spring.factories` registration and default-property
  precedence) belongs in the fast gate.
- `*IT`: anything that boots a server, run by failsafe at `integration-test`.

`peekaboot-backend`'s suite uses no `@SpringBootTest` and no embedded server; a bare
`AnnotationConfigApplicationContext` covers the one case where a bean-name lookup needs a real
container (`ServerUrlResolverTest`). `peekaboot-spring-boot-autoconfigure` has context-runner
unit tests for its auto-configurations, plus the `*IT`s that boot its own `TestApplication`
(`DevToolbarAutoConfigurationIT` and `PeekabootOffIT` as `@SpringBootTest`, `StartupBannerIT`
through `SpringApplicationBuilder`). Everything Playwright lives in `peekaboot-testing-app`
under `org.peekaboot.testingapp.ui`, which boots the sample app and drives the real
dashboard/toolbar/overlay in a headless browser. `peekaboot-frontend` has no test sources of
its own; that suite covers it.

### Testing auto-configuration

Use `ApplicationContextRunner` with `FilteredClassLoader` for unit tests, as
`PeekabootAutoConfigurationTest` does to prove a missing actuator endpoint class disables the
whole configuration:

```java
contextRunner
    .withPropertyValues("peekaboot.enabled=true")
    .withClassLoader(new FilteredClassLoader(HealthEndpoint.class))
    .run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean(PeekabootController.class);
    });
```

For ordering, use a real OpenTelemetry stack rather than a mock `Tracer`:
`DevToolbarAutoConfigurationIT` is a `@SpringBootTest(classes = TestApplication.class,
webEnvironment = RANDOM_PORT)` on the `integration` profile, pulling in
`spring-boot-starter-opentelemetry` so the `Tracer` bean is the real one.

## Fixtures
`peekaboot-backend`'s fixtures are built through `org.peekaboot.backend.testsupport`.
`Spans.span(id)` builds a `SpanData` with neutral defaults, alongside the `query` preset for a
JDBC-shaped query span, `resultSet(id, rowCount)` for the row-count span datasource-proxy
exports after one, `jdbcQuery`/`jdbcDuplicate` for the double-instrumented pair and
`jdbcConnection` for the pool acquisition datasource-micrometer exports, and `asyncTask` for the
span Peekaboot's async decorator exports. A query fixture's bind parameters are its
`QueryParameterTags.PARAMETERS_TAG` tag, in the JSON `QueryParameterObservationFilter` writes
(`.tag(QueryParameterTags.PARAMETERS_TAG, "[[\"42\"]]")`); `SpanNodes.node(id).statement(...)`
takes the mapped `SqlStatement`.
`TraceDatas.of(traceId, spans...)` runs those through a `TraceDataBundle` and returns its
`snapshot()`, so a mapper test gets the root and ordering the store would hand it.
`SpanNodes.node(id)` builds an already-mapped `SpanNode`, `TraceTrees.tree(rootSpan)` the
mapped `TraceTree` around one. `Logs.log(traceId)` builds a `LogCapturedEvent`,
`RequestCompletedEvents.request(traceId)`/`minimal(traceId)` the request event, and
`TraceStores.withDefaults()`/`with(customizer)` an `InMemoryTraceStore` built the way the
auto-configuration builds it, from `PeekabootTracingProperties`.
`LifecycleStarts.start(epochMs)` builds the START event the lifecycle tests replay; a stop
is `LifecycleEvent.stop`.
`SeriesDefs.value(id, meter)` is the plain value series the collector tests share.
`Credentials.generated(password)`, and `loaded`, `configured` and `unpersisted` beside it,
build the guard's `DashboardCredentials` in each origin, for one user at one fixed instant.
`InsightsCollectors.noOpListener()` is the collector listener for a test that reads the rings
rather than the events. `InsightsCollectorBuilder.collector(registry)` builds the collector with
those defaults; it sits in the `insights` test package because the clock is the collector's only
package-private constructor argument. `Latches.awaitQuietly(latch)` waits inside code that cannot
throw, such as a gauge a test parks in.
`FailingWriteResponse` is the `MockHttpServletResponse` whose writes fail the way a container's
do (first write, or every write), and `PosixPermissions` reads a path's mode for the tests that
pin owner-only storage. `Requests.get(path)`/`request(method, path)` build the servlet request a
filter test sends, and `CurrentSpans.stub(tracer, traceId)` makes a mocked `Tracer` report a
current span. OpenTelemetry `SpanData` comes from the SDK's own
`opentelemetry-sdk-testing` (`TestSpanData.builder()`), not a hand-rolled implementation.
A test names only what it asserts on. A new record component is added to the builder once, not
to every test class. The domain records carry no test-only constructors, and a test does not
wrap a builder in a positional helper of its own (`createSpan(id, 150, OK, tags, children)`):
that hides the defaults the builder exists to make explicit. `peekaboot-testing-app` cannot
reach `Spans` (the backend publishes no test jar), so its `integration/TestSpans` is the same
builder shape for the ITs that write spans straight to the store. `peekaboot-spring-boot-autoconfigure`
needs only a few fixed shapes, so its `TraceFixtures` names them (`addRootSpan`, `query`,
`rootSpanLasting`, `vendorFrameLog`) instead.

`MaskingEngineTest`'s provider fixtures are split literals (`"xoxb" + "-123..."`) on purpose.
The file is full of strings shaped exactly like the credentials the engine detects, and
GitHub's push protection scans for the same shapes. The concatenation folds at compile time,
so the value under test is unchanged. It just never appears contiguously in the source. A new
provider fixture is written the same way and never tidied back together.

## Accessibility
`AccessibilityIT` pins the regressions this project has had (a control that lost its
accessible name, a target under 24px, a contrast pair), one assertion each, with the reasoning
in the Javadoc. `AccessibilitySweepIT` runs axe-core (`com.deque.html.axe-core:playwright`,
pinned in both builds) over the dashboard, the trace overlay and the toolbar's shadow root at
WCAG 2.1 A and AA, and expects zero violations. The two do not replace each other: the sweep
knows rules nobody here thought of, and says nothing about why a targeted assertion exists. The
overlay is swept over the dashboard rather than over a sample-app page, and the toolbar is
scoped to its own host: the consumer's markup is not Peekaboot's to answer for.

## Backend to frontend contracts
The frontend is plain ES modules, so a Java enum and its JS mirror drift silently.
`SharedModuleIT`'s `*MirrorTheBackend*` tests pin `ROOT_ACTION_TYPES`, `TASK_TYPES`,
`MIGRATION_STATES`, `ISSUE_SEVERITIES` and `LOG_LEVELS` to `RootActionType`, `TaskType`,
`MigrationState`, `IssueSeverity` (its wire words) and Logback's levels, the keys the frontend reads off
`/api/features` to the `Features` record. `ToolbarDataProviderTest` pins the toolbar blob's
`features` names to the same record. A vocabulary that gains a JS mirror gets a row there. The same suite pins
`format.js`'s `formatLongDuration` against `UptimeFormat.humanize` unit by unit, and drives
`dashboard/tabs/insights-store.js` (the browser's mirror of the insights rings) directly, so
the JS with no Java counterpart is covered too. `SqlStatement`'s JSON (`text`, `formatted`,
`parameters`) has no mirror to pin; `SqlStatementViewIT` renders a real one from the testing
app's person lookup, and `SqlViewModuleIT` covers the view's branches against hand-built
statements.

## Real collaborators over mocks
Mock only when the real dependency is expensive, non-deterministic, or external: servlet
machinery, Micrometer `Tracer`/`Span`, Spring container callbacks, JDBC `DataSource`, or an
in-module class whose real construction needs a live container. Cheap in-module classes
(`InMemoryTraceStore`, `QueryExtractor`, `ToolbarDataProvider`, the mappers) are used for real.
Spring and servlet machinery (`MockMvc`, mock requests) is fine. `InsightsSsePublisherTest`
shows how far that reaches: it drives Spring's real `ResponseBodyEmitterReturnValueHandler` and
`StandardServletAsyncWebRequest` over mock servlet objects, so a container timeout runs the
interceptor chain a stubbed emitter would have skipped. Its `DispatchedStream` still hands that
handler a plain `ServletWebRequest`, where `RequestMappingHandlerAdapter` hands it the
`StandardServletAsyncWebRequest` itself, whose response wrapper raises
`AsyncRequestNotUsableException` on a write once the async request has errored. So every send
failure in the class is stated by the response fixture rather than produced by that state, and
closing the gap means routing the class's writes through the wrapper and re-deriving what each
of them then fails with.

One exception: controller tests that stub a service and assert `isSameAs` pass-through
(sentinel-identity delegation), even where the service is cheap to construct. A real service
instance offers no sentinel object for `isSameAs` to check, so the assertion structurally
requires a stub. The rule targets cheap in-module collaborators asked to compute something,
never this pass-through shape.

## Pristine output
Test output must be silent. The console shows WARN and ERROR only, and every such line a test
provokes is asserted and filtered, or silenced by a logger level listed below, so a green build
prints none. No stack traces.

- `peekaboot-testing-app`'s and `peekaboot-spring-boot-autoconfigure`'s `logback-test.xml` put a
  `ThresholdFilter` at `WARN` on the console appender, so a test that boots an application does
  not print its startup log. The threshold sits on the appender, never on a logger or the
  root: Peekaboot's appender on the root logger must keep receiving INFO events, which tests
  assert through Peekaboot's trace API. `peekaboot-backend` and `peekaboot-test-support` carry
  no `logback-test.xml`, and their tests print nothing.
- Where a test's subject is the JavaScript itself, `page.onPageError` collects browser errors
  into a list the test asserts on. Classes whose subject fails invisibly (the charts, the
  lifecycle table) opt into `PlaywrightTestBase.captureBrowserSignals()`, which collects every
  console message, page error, failed request and error response the test provokes and prints
  them only when the test fails (a `TestWatcher`; a green run prints nothing). Collection stops
  when teardown starts, so the `about:blank` navigation aborting the Insights tab's EventSource
  is not recorded. There is no shared listener that filters, and no allow-list. Where an
  engine's own behaviour is the subject, the test carries a `chromium-only` tag and the other
  engines skip it; nothing is excused at the output.
- Tests that trigger error paths capture the log event (logback `ListAppender`) and assert it
  instead of letting it print. The shared helper is `org.peekaboot.testsupport.LogCapture` from
  [`peekaboot-test-support`](../peekaboot-test-support/README.md).
- `peekaboot-testing-app`'s `logback-test.xml` sets `org.apache.catalina.core.ContainerBase` to
  `OFF`. `OrderController`'s deliberately failing `/boom` endpoint escapes as an unhandled
  exception, which embedded Tomcat logs as a full stack trace under a per-JVM
  instance-numbered logger name (`Tomcat`, `Tomcat-1`, ...). Logback has no wildcard matching
  and cannot target that directly, and `ContainerBase` is the narrowest static ancestor covering
  every run. Accepted: the module configures no clustering and no custom realm, the only other
  things that logger silences, and the application's own error logging is unaffected. A real
  failure still prints and is still asserted.
- `peekaboot-spring-boot-autoconfigure`'s `logback-test.xml` silences the same logger for the same
  reason, there for `ErrorDispatchIT`'s deliberately failing `/throwing` endpoint. It also raises
  `org.flywaydb.core.internal.database.base.Database` to `ERROR`, for the H2-version warning
  described below.
- `peekaboot-testing-app`'s `logback-test.xml` raises
  `org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver`
  to `ERROR`. Playwright teardown aborts in-flight JSON responses, and the resolver WARNs
  `Ignoring exception ... Broken pipe` for each aborted write. That is Spring-side teardown
  noise. A failure the resolver really handles still reaches the client as a 4xx/5xx and fails
  the asserting test, so only that advisory WARN is lost.

- `peekaboot-testing-app`'s `logback-test.xml` replaces Boot's console appender with one carrying
  `IntentionalLogFilter` entries (`peekaboot-test-support`). Each entry hides one line the tests
  provoke on purpose. An entry matches the logger and the whole message, so the same class
  logging anything else still prints.
- Seven of those lines are asserted through Peekaboot's own trace API: `PersonController`'s
  `?error=true` error (`LogCaptureIT`), the `Scheduler` error (`TraceDeepLinkIT`), the
  `OrderController` `/boom` error (`OrderTraceCaptureIT`), `OrderReconciler`'s warning
  (`TracesTabIT`), `PartnerFeedImporter`'s warning and error (`LogsTabIT`) and the override
  page's `PeekabootErrorExceptionResolver` error (`ErrorPageContentNegotiationIT`). A filter on the console appender leaves Peekaboot's appender on the root
  logger receiving the event. `LogCapture` detaches the logger from the root, so Peekaboot would
  never see the line. A logger level would hide it from Peekaboot too.
- Two more entries cover product warnings. The `SecurityPostureListener`
  banner is logged once per context, by whichever class happens to load it first. Only
  `SecurityPostureListenerTest` asserts the banner. `SeriesSampler` warns about the
  `absent-subsystem` panel that `insights-test-panels.yml` adds on purpose, a few ticks after each
  test-profile context starts, too early to catch reliably; `InsightsServiceTest` asserts it.
- The same file raises `io.netty.resolver.dns.DnsServerAddressStreamProviders` and
  `org.flywaydb.core.internal.database.base.Database` to `ERROR`. The first warns about a
  macOS-only native jar. The second warns that H2 is newer than the version Flyway has verified.

### Accepted, unavoidable noise
Third-party output no setting in this build can switch off. This is not a list of known flaky
tests, and Peekaboot keeps none: a test that passes on a re-run is a defect to root-cause.

- `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because
  bootstrap classpath has been appended`. A lowercase-`warning` JVM CDS notice printed once per
  forked JVM, caused by Mockito's inline mock-maker calling
  `Instrumentation.appendToBootstrapClassLoaderSearch` during its javaagent bootstrap. No config
  knob exists for it.
- The `sun.misc.Unsafe` deprecation `WARNING:` block, printed before the build's own first
  line. It comes from the Maven distribution's own guava
  (`AbstractFuture$UnsafeAtomicHelper` in `<maven home>/lib/guava.jar`), not from anything on
  this project's class path, and depends only on which `mvn` runs the build. The pinned
  Maven `./mvnw` downloads ships a guava that no longer calls it, so a wrapper build is
  silent and an older `mvn` on the PATH is not.

## Teardown and the toolbar's fetch ladder
`PlaywrightTestBase.closePage()` navigates to `about:blank` before `context().close()`, and
catches `TargetClosedError` around the close as insurance. The collapsed toolbar's fetch ladder
(`toolbar.js`) polls `/api/traces/{id}/insights` for up to 4.75s after page load, independent of
any one test's lifetime. The tests that route that traffic never unroute it:
`TraceOverlayIT.closeButtonDismissesTheOverlayOnTheErrorPath`;
`ToolbarIT.toolbarShowsPendingWhenTheTraceRequestFails`, which deliberately waits out all four
ladder attempts before teardown; and
`ToolbarIT.openOverlayImportFailureIsCaughtAndLeavesTheBarUsable`, which routes
`trace-detail.js` and must *not* unroute, because the browser's module map caches the failed
dynamic import.

A scheduled poll firing while the close is mid-flight makes Playwright's client sync
interception patterns against a target that is already closing. The `about:blank` navigation
stops the pollers first. Per-test `unroute()` calls would not close the race, since `unroute` is
itself an interception update over the same wire. Each catch logs what it swallowed at WARN, so
a teardown hiding a real failure still leaves a line in the output.

## Requests the browser loses

Chromium drops every request in flight, with `net::ERR_NETWORK_CHANGED`, whenever the host's
network configuration changes. A container taking a veth interface up or down is enough: the
host side's link-local address arriving about a second after the start drops whatever is in
flight. The suite's own Testcontainers classes did this to the browser tests running beside
them, so they carry `@StartsContainers`, which isolates them from every other class. Containers
started outside the build cannot be kept out. On a machine with other container churn it still
lands a couple of times in a four-minute run. The dashboard is where it showed: no build step
means `main.js` is a graph of forty-odd separate fetches, and losing one left the shell
unevaluated, the loading placeholder up and the page mute, which every waiting test read as a
30s Playwright timeout with nothing to explain it.

`dashboard/boot-recovery.js` now reloads the page once out of it and raises the error banner if
that does not help (see `peekaboot-frontend/README.md`, *When the shell does not start*). The
recovery is the product's, not the suite's, so it covers the deep-link tests that navigate to
the dashboard directly as well as `openDashboard()`, and no test retries anything.

`ContextPathToolbarIT` is the one class that has to know: it fails on any Peekaboot request the
page asked for and did not get, and a dropped request is not the context-path bug it hunts, so
it excludes that one failure by name.

Pages carrying the toolbar keep the same exposure over a much smaller graph, and are
deliberately left without an equivalent recovery. The only recovery on offer is a page reload,
and those pages belong to the host application: reloading one discards whatever a user has typed
and not saved, which is a worse bug than the mute bar it would fix. The bar degrades visibly
anyway. `ToolbarShell` renders its "could not start" notice into every page, `toolbar.css`
reveals it 400ms after paint with a delayed animation, and `toolbar.js` removes it the moment it
runs, so a script that never arrives leaves a reader with a notice and a real link to the
dashboard. A test waiting for `data-pk-ready` still times out, and that is the accepted cost.

### Keeping container churn on a Linux host away from the browser

The trigger is the IPv6 link-local address the kernel gives each new host-side veth and bridge.
Links going up or down without an address change drop nothing. Chromium's Linux
`AddressTrackerLinux` reports every netlink address change with no interface filter, and the socket
pools answer each one with `FlushWithError(ERR_NETWORK_CHANGED)` (`net/base/address_tracker_linux.cc`,
`net/socket/transport_client_socket_pool.cc`, `net/http/http_stream_pool.cc`; checked at Chromium
153.0.8010.12, the build Playwright 1.63 bundles). No switch or feature turns it off: the only
opt-out, `HttpNetworkSessionParams::ignore_ip_address_changes`, is internal. Firefox and WebKit
complete every request under the same churn.

A host that runs other projects' containers next to the suite can stop generating those addresses
for links created after boot. Docker 29 writes no IPv6 sysctl on the host-side veth, and on a
bridge only when the network enables IPv6, so the kernel default decides. Pin the uplink back to
the normal mode, since it still needs its link-local address for router advertisements (`ens18`
here; the systemd udev rule applies per-interface keys as a link appears):

```
# /etc/sysctl.d/90-no-linklocal-on-new-links.conf
net.ipv6.conf.default.addr_gen_mode = 1
net.ipv6.conf.ens18.addr_gen_mode = 0
```

Apply it with `sysctl --system`. Links that already exist keep their addresses. A Docker network
with IPv6 enabled expects the kernel's link-local address on its bridge and is untested under this
setting, so check `docker network inspect` for `EnableIPv6` first.
Creating or removing a Docker network still adds or drops the bridge's IPv4 address, which
Chromium also counts as a change; that happens far less often than a container start. Neither the
Mac (Docker Desktop keeps its links inside a VM) nor CI (the suite's own containers are already
isolated) needs the setting.

## Geometry waits on the bundled webfont

Peekaboot serves Geist from its own jar, so a size or position assertion has to wait for the
face: a measurement taken while it loads measures the system fallback and passes anyway.
`Fonts.awaitReady(page)` is that gate. It waits on `document.fonts.status`, not
`document.fonts.ready`, which re-arms per face and carries no timeout through `page.evaluate`.

The three points a surface first becomes measurable own the wait: `openDashboard()`,
`openPersonsPage()` and `overlay.awaitLoaded()`. A test that navigates straight to a deep link
passes through none of them and opens it with `openTraceDeepLinkToMeasure(traceId, selector)`
instead.

A loaded face is not a painted one on the dashboard. tokens.css uses `font-display: optional`, so
a face that arrives after its block period reports `loaded` while the document keeps the fallback
for its lifetime. A cold font fetch on a loaded machine does that. `openTraceDeepLinkToMeasure`
measures the Spans tab's header text with and without Geist and reloads while the two widths
match.

## Isolation in shared Spring contexts
No class in this module clears a mutable singleton the suite shares, and none holds a
`@ResourceLock`. `*IT` classes run concurrently, so a class that cleared the `TraceStore` would
have to take `@ResourceLock(..., mode = READ_WRITE)` over it while every class pinning its own
data there took the `READ` side. That lock would serialize half the suite for one class's exact
counts. Pinning is the cheaper answer and the one every class here takes: assert on your own
trace, never on what the store holds. A class on its own context configuration is isolated
anyway.

Pinning to a traceId does not mean searching the store for it. Every captured request answers
with `Server-Timing: trace;desc="00-<traceId>-..."`, whatever its content type or status.
`TraceApiClient.get(path)` returns that id for a GET and `traceIdOf(headers)` reads it off a
response made some other way, so an integration test names the trace its own call produced
before it reads anything back. A scheduled job answers no request and so has no such header;
`integration/ScheduledJobs.run` reads its id off the scheduled-task observation instead (the
recorder is a bean of `DeferredSchedulingConfig`) and returns it, which is how the two classes
firing `Scheduler.fixedRate` each wait for the run they fired.

Spans reach the store asynchronously (the OTel batch processor, 50 ms in the test profile), so
a test waits for the fact it is about to assert on, never for a delay.
`PlaywrightTestBase.awaitTrace(traceId, jsPredicate)` and `awaitListedTrace(query, jsPredicate)`
poll from the browser; `TraceApiClient.awaitTrace(traceId, predicate)` and its listing waits
poll over HTTP with Awaitility. `ROOT_SPAN_EXPORTED` on both is the completeness proof: only a
SERVER root classifies HTTP_REQUEST, that root is the last span of a request to end, and the
exporter hands spans over in the order they ended. The listing endpoint leaves a trace out
until its root has arrived, so a listed match already carries its spans.

Two shared things have no lock and need care instead. The JVM-wide Logback context is one. Boot
assumes one application per JVM. Closing a context marks Logback uninitialised, and the next
context to start then denies every log event JVM-wide until it has reset Logback, which also
detaches Peekaboot's capture appender. Even a start that finds Logback configured denies every
event for a moment. A request another context serves in such a window is traced with no logs
against it. `SharedJvmLogbackLoggingSystem`, registered in the module's test
`META-INF/spring.factories`, configures Logback on the JVM's first start and makes every later
start leave it alone, so after that first start no start denies a log event or detaches an
appender. `ContextBootLoggingIT` pins both. A trace's logs are therefore final once its root span
is stored, and a test waits for the log it asserts on like any other fact. The same context is
why `ActuatorReadingsIT` re-levels and redirects one logger for the length of each of its
methods, which every concurrently running class sees. That is inert there because only its own
context has an endpoint that throws, and the only real remedy, `@Isolated`, stops the whole suite
for the class.

The database needs no such discipline. `h2-test-infrastructure.yml`, which the `test`, `security`
and `auto-security` profiles import, sets no `spring.datasource.url`, so Boot's `generate-unique-name` default gives every context its own
`jdbc:h2:mem:<uuid>`. H2 keys in-memory databases by name per JVM, so a shared fixed name would
let each lazily booted context's `ddl-auto: create-drop` wipe the tables under whichever test is
mid-flight. Two URLs name a database because they need a compatibility mode: `FlywayTabIT`'s
(`MODE=PostgreSQL`) and the inventory DataSource's in that shared file (`MODE=MySQL`), whose
`inventory-${random.uuid}` name keeps it per context all the same.
The `TraceStore` is the only state the shared context's classes contend for.

## Spring Security on the testing-app classpath
`peekaboot-testing-app` carries `spring-boot-starter-security` in test scope for
`SecuredPeekabootIT` and `SecuredDashboardIT` alone, which prove the `SecurityFilterChain` the
website's security page publishes. Test scope keeps it out of the repackaged jar and out of
`spring-boot:run`, so the sample app itself still starts unsecured.

Left auto-configured, it would put `anyRequest().authenticated()` in front of every
`@SpringBootTest` in the module. So `servlet-security-exclusions.yml` excludes the servlet
security auto-configuration (`UserDetailsServiceAutoConfiguration`,
`ServletWebSecurityAutoConfiguration`, `ManagementWebSecurityAutoConfiguration`), and the `test`,
`auto-security` and `screenshots` profiles each import it with `spring.config.import`, since
`AutoSecuredDashboardIT` and `ScreenshotCapture` never load `application-test.yml`. The security
tests run under their own `security` profile, which imports the H2 infrastructure and no
exclusion, so neither has to undo a module-wide setting.

One gotcha: an inlined `spring.autoconfigure.exclude=...` property *replaces* the profile's
value for that key rather than merging with it, so a context setting its own exclusion list
appends `${servlet-security-exclusions}`, as `TracesTabWithoutSpanSourceIT` does. Symptom when it
is missed: Boot logs `Using generated security password` for that context.

The example config itself, `PeekabootSecurityConfig`, lives in `org.peekaboot.example.security`,
outside `TestingApp`'s component-scan root on purpose, since a stray `SecurityFilterChain` bean
would fail the context of every test that excluded the auto-configuration. Both name it in
`@SpringBootTest(classes = ...)`.

## Running
[`BUILD.md`](../BUILD.md#commands) owns the commands, the gate list and the testing-app's
thread and engine flags. What a test author needs on top of it:

- `peekaboot-testing-app` runs its `*IT`s as concurrent classes in one JVM, so a test asserting
  on app-global state pins to its own traceId (see
  [Isolation in shared Spring contexts](#isolation-in-shared-spring-contexts)).
- Write-path benchmark, excluded from the default suite:
  `./mvnw -pl peekaboot-backend test -Dtest=TraceWritePathBenchmark`
- The website's screenshots come from `ScreenshotCapture`. Its command, and what it may and may
  not reveal, are in
  [`peekaboot-testing-app/README.md`](../peekaboot-testing-app/README.md#screenshot-capture-screenshotcapture).

## Counting tests
Surefire's per-class `.txt` summaries report `Tests run: 0` for classes using `@Nested`
(`PeekabootControllerTest`, `MaskingEngineTest`), so summing them under-reports. Counting `@Test`
annotations is also wrong: it misses `@ParameterizedTest`, and `MaskingEngineTest`'s
parameterized tests each expand to many invocations. Count from the XML, or from the reactor
summary, never from the `.txt` files:

```bash
for f in <module>/target/surefire-reports/TEST-*.xml; do
  grep -m1 -o 'tests="[0-9]*"' "$f" | grep -o '[0-9]*'
done | paste -sd+ | bc
```
