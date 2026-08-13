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
  the shared `net.osslabz.peekaboot.backend.testsupport.LogCapture` helper in
  `peekaboot-backend`) and assert it instead of letting it print.
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

## Isolation in shared Spring contexts
`@SpringBootTest` classes sharing mutable singletons (e.g. `TraceStore`) reset
that state first thing in `@BeforeEach` (`traceStore.clear()`), so tests assert
exact counts, never defensive `contains`.

## Running
- Full suite: `mvn test` (root). Single class: `mvn -pl <module> test -Dtest=<Class>`
  — never combine `-am` with `-Dtest`.
