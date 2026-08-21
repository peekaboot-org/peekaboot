# macOS-only failure: two Playwright tests time out on zero captured logs

**Status:** unresolved. Reproduces on macOS, not on Linux.
**Branch:** `refactor/frontend-design-system` (unpushed; synced to the Mac via mutagen).
**Verify you are on the same tree before trusting anything below:** `git rev-parse HEAD` should be
`27b204c` or a descendant.

---

## The symptom

`mvn clean install` on macOS:

```
[ERROR] ToolbarTest.toolbarShowsErrorLogCountWhenRequestLogsAnError:192 » Timeout 15000ms exceeded
[ERROR] TraceOverlayTest.logsFilterChipUsesTheContrastTunedForeground:116 » Timeout 15000ms exceeded
[ERROR] Tests run: 153, Failures: 0, Errors: 2, Skipped: 0
```

Same command on Linux (Temurin 25.0.4, 8 cores): **BUILD SUCCESS, 153/153**.

## What the two failures actually mean

They are **one bug, not two**. Both are the only tests in the suite that depend on a captured log
entry, and both hit `/?error=true`, which makes `PersonController.index` emit a real ERROR
(`PersonController.java:31`).

- `ToolbarTest.java:192` waits for `#pk-metrics` to contain `err`. That string is only rendered when
  `summary.logs.errorCount > 0` (`toolbar/toolbar.js:160-163`).
- `TraceOverlayTest.java:116` waits for `.pk-log__span`, which `renderLogRows` emits **one per log
  row** (`trace-detail/tabs/logs.js:13-19`). Zero rows means zero logs.

Critically, `TraceOverlayTest` gets **past** line 102 (trace id is non-`-`) and line 110 (the logs tab
exists) before failing at 116. So the trace exists, the toolbar populates, the overlay opens and the
tab strip builds. **Only the log entries are missing.** Any theory that breaks tracing generally is
wrong.

## Why this path is fragile (read this before theorising)

`PeekabootLogbackAppender.append` drops any event whose MDC has no `traceId`
(`peekaboot-backend/.../log/PeekabootLogbackAppender.java:46-51`):

```java
Map<String, String> mdc = event.getMDCPropertyMap();
String traceId = mdc != null ? mdc.get(TRACE_ID_KEY) : null;
if (traceId == null || traceId.isBlank()) {
    return;
}
```

**peekaboot never populates that MDC itself** — verified, there is no `MDC.put` anywhere in
`peekaboot-backend/src/main` or `peekaboot-spring-boot-autoconfigure/src/main`. It relies entirely on
Micrometer's OpenTelemetry→SLF4J bridge.

Meanwhile the toolbar's own trace id comes from a **completely different source** —
`tracer.currentSpan().context().traceId()` in `DevToolbarFilter.java:132`.

**Consequence:** a broken MDC bridge produces a fully populated toolbar whose log counts are all
zero. That is exactly the observed symptom, and it is why the browser tests fail as opaque timeouts
rather than as informative assertions.

## Ruled out — with evidence, not reasoning

Do not re-litigate these without new data.

| Hypothesis | How it was killed |
|---|---|
| Log arrives before the span and is discarded | `InMemoryTraceStore.addLog:91` → `resolveBundle:115` creates or reuses a bundle. Nothing is dropped. |
| Per-trace log cap discards the ERROR | `TraceDataBundle.addLog:46-55` evicts the **oldest**. A late ERROR always survives. |
| Log orphaned because its span was deduplicated | `TraceInsightsService.enrichWithDetails:198,219` computes the summary and the flat list from **all** logs, independent of span attachment. |
| Logs filtered by a time window vs span clocks | No such filter exists; logs map straight through at `:167-176`. |
| `SharedToolbarTestConfig`'s NOOP `DeterministicTracer` leaking into the Playwright context | It is `@TestConfiguration` (line 27) — excluded from component scan. |
| Test-order dependence (surefire default `runOrder=filesystem` genuinely differs between APFS and ext4) | Full run with `-Dsurefire.runOrder=reversealphabetical`: **153/153 green**. |
| Timing race | 3 consecutive runs under 10 CPU burners on 8 cores — `ToolbarTest` slowed 10.5s → 50s — **all green**. |
| `clean` vs incremental | Full `mvn clean install` on Linux: **exit 0**. |
| Scheduler flooding the store with error traces | `Scheduler.fixedRate` is hourly, `fixedDelay` 5-minutely. One shot each per run. |
| Ambiguity between `logback-test.xml` and `logback-spring.xml` | Deterministic: Spring Boot's self-initialisation picks `logback-test.xml` in tests and ignores the `-spring` variant. Not OS-dependent. |

## Surviving hypotheses

1. **MDC correlation is not populated on macOS.** The OTel→SLF4J bridge installs a JVM-global,
   effectively one-shot `ContextStorage` wrapper. If anything touches OTel context before it is
   installed, MDC stays empty for the whole JVM and every captured log is silently dropped. Most
   likely sub-cause: a different resolved version of micrometer-tracing / opentelemetry from a
   differing local `~/.m2`, or a different JDK.
2. **A frontend render race.** `toolbar.js:98` gates on spans only:
   ```js
   return trace && trace.rootSpan && trace.summary && trace.summary.spans && trace.summary.spans.count > 0;
   ```
   It renders **once** and then stops polling forever, so any log attached after that instant is lost
   permanently. On Linux this cannot bite, because logs are published synchronously during the
   request whereas spans arrive via the OTel `BatchSpanProcessor` (50ms — see
   `application-test.yml`), so logs always land first. If macOS reorders that, this fires.

## Start here: the discriminator

`LogCaptureIntegrationTest` (added in `27b204c`) covers this path headlessly in ~17s. It exists
because **nothing else in the repo tests MDC-correlated capture** — those two Playwright tests were
its only coverage.

```
mvn -o test -pl peekaboot-testing-app -Dtest=LogCaptureIntegrationTest -DfailIfNoSpecifiedTests=false
```

- **RED** → hypothesis 1. The bug is backend capture; MDC is empty. Ignore the frontend entirely.
- **GREEN, while the two Playwright tests still fail** → hypothesis 2. The bug is in the frontend
  render/poll logic, and the backend is fine.

The test is deliberately mutation-resistant: one case asserts `errorCount == 1` for `/?error=true`
and a second asserts `0` for `/persons` through the identical helper, so neither can pass
tautologically.

Also collect:

```
java -version && mvn -version
mvn -o dependency:tree -pl peekaboot-testing-app -Dincludes='io.micrometer:*,io.opentelemetry:*'
```

Linux reference: Temurin 25.0.4, 8 cores, everything green.

A quick way to see MDC directly — console output carries the correlation field. On Linux a healthy
run prints the traceId in brackets:

```
[-auto-1-exec-10] [d75679801697a22b415867a57eb805a6-9badab3540dfc96d] o.p.t.controller.PersonController : An error occurred while trying to find all persons
```

**If that bracket is empty on macOS, hypothesis 1 is confirmed outright** and no further bisection is
needed.

## Traps

- **Do not "fix" this by loosening `isTraceComplete` to also wait for logs.** If the cause is
  hypothesis 1, that change makes a real backend failure invisible and the tests would then hang or
  pass by accident. Establish which hypothesis holds first.
- **Do not weaken, skip, or delete the two failing tests.** They are the only coverage of a real
  feature, and they are correctly detecting something.
- The Linux side is fully green, so a change that makes macOS pass must not be validated on macOS
  alone — it has to keep 155 tests green on Linux too (153 + the 2 new ones).
- `peekaboot-backend` deliberately has no dependency on `peekaboot-frontend`; browser-level tests
  belong in `peekaboot-testing-app`.

## Key file references

| Concern | Location |
|---|---|
| MDC gate that drops logs | `peekaboot-backend/src/main/java/org/peekaboot/backend/log/PeekabootLogbackAppender.java:46-51` |
| Appender attach / detach lifecycle | `peekaboot-spring-boot-autoconfigure/src/main/java/org/peekaboot/autoconfigure/DevToolbarAutoConfiguration.java:94-120` |
| Toolbar trace id (different source than MDC) | `peekaboot-backend/src/main/java/org/peekaboot/backend/filter/DevToolbarFilter.java:132` |
| Store: add log / resolve bundle | `peekaboot-backend/src/main/java/org/peekaboot/backend/tracing/store/InMemoryTraceStore.java:91,115` |
| Summary + flat log list | `peekaboot-backend/src/main/java/org/peekaboot/backend/service/TraceInsightsService.java:167-221` |
| Toolbar completeness gate + error badge | `peekaboot-frontend/src/main/resources/static/peekaboot/ui/toolbar/toolbar.js:98,160` |
| Overlay log rows | `peekaboot-frontend/src/main/resources/static/peekaboot/ui/trace-detail/tabs/logs.js:13-19` |
| The two failing tests | `peekaboot-testing-app/src/test/java/org/peekaboot/testingapp/ui/ToolbarTest.java:189`, `.../TraceOverlayTest.java:96` |
| Headless discriminator | `peekaboot-testing-app/src/test/java/org/peekaboot/testingapp/integration/LogCaptureIntegrationTest.java` |
| Test profile (50ms span export) | `peekaboot-testing-app/src/test/resources/application-test.yml` |
