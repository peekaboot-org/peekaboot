# Log capture dies when a second Spring context starts

**Status:** resolved. Fixed in `LogbackCaptureReinstaller` + `DevToolbarAutoConfiguration.LogbackAppenderRegistrar`.

Originally filed as a macOS-only failure of two Playwright tests. It is not OS-specific: it is a
latent bug in peekaboot's appender registration that surefire's class order decides whether to
expose. macOS (APFS) happened to order the classes so it fired; Linux (ext4) happened not to.

---

## The symptom

`mvn clean install`:

```
[ERROR] ToolbarTest.toolbarShowsErrorLogCountWhenRequestLogsAnError:192 » Timeout 15000ms exceeded
[ERROR] TraceOverlayTest.logsFilterChipUsesTheContrastTunedForeground:116 » Timeout 15000ms exceeded
[ERROR] LogCaptureIntegrationTest.errorLoggedInsideRequestIsCapturedAgainstThatRequestsTrace:83
        [... a count of 0 means the log event carried no MDC traceId]
```

All three are the same bug: the only tests that depend on a captured log entry. The toolbar, the
trace, the overlay and its tab strip all populate correctly — **only the log entries are missing**.

## Root cause

`LoggingApplicationListener` re-initialises Logback on *every* `ApplicationEnvironmentPreparedEvent`,
i.e. every time any Spring application starts in the JVM:

```
LoggerContext.reset()                                  <- detaches AND stops every appender
  LogbackLoggingSystem.reinitialize(:347)
  LoggingApplicationListener.onApplicationEnvironmentPreparedEvent(:248)
  SpringBootContextLoader.loadContext(:155)
```

`LoggerContext.reset()` runs `root.recursiveReset()` → `detachAndStopAllAppenders()`. Appenders
declared in `logback.xml` are recreated by the Joran run that follows; `PeekabootLogbackAppender` is
registered in code from `@PostConstruct` and is not. **The application context that is still serving
requests never learns of this**, so it keeps recording traces while capturing no logs against them —
a fully populated toolbar whose log counts are all zero.

A Logback `LoggerContextListener` cannot fix this on its own, even a reset-resistant one.
`LogbackLoggingSystem.stopAndReset` does a double tap:

```java
loggerContext.stop();   // :309 -> reset() -> fires onReset, THEN resetAllListeners()
loggerContext.reset();  // :310 -> detaches again, with no listeners left to hear it
```

`stop()` drops every listener, reset-resistant ones included, so the immediately following `reset()`
is unobservable from inside Logback. The repair has to come from outside it.

## Why it looked macOS-only

Surefire's default `runOrder=filesystem` yields different class orders on APFS and ext4. In the
failing order `ThemeTokenTest` creates the context shared by all Playwright tests *and* by
`LogCaptureIntegrationTest` (identical `@SpringBootTest` cache key), then `FlywayTabTest` — which has
`@TestPropertySource` and therefore its own context — starts ten seconds later and resets Logback.
Every log-dependent test after that point runs in the now-deaf first context.

Minimal reproduction, single variable:

| Classes, `-Dsurefire.runOrder=alphabetical` | Before fix |
|---|---|
| `ComponentPrimitiveTest` → `ToolbarTest#toolbarShowsErrorLogCount…` | pass |
| `ComponentPrimitiveTest` → **`FlywayTabTest`** → `ToolbarTest#toolbarShowsErrorLogCount…` | **fail** |

The `reversealphabetical` run recorded in the original notes as *ruling out* order dependence in fact
confirmed it — it merely picked an order where the log-dependent tests ran before any foreign context
existed. Re-run on the pre-fix tree it fails too, on `LogCaptureIntegrationTest`.

## Hypotheses that were wrong

Both of the original surviving hypotheses were refuted by evidence:

| Hypothesis | How it was killed |
|---|---|
| MDC correlation not populated on macOS | The console correlation field carries the exact asserted trace id: `[fe8f1ab7b609753be5a80367f3006fe0-156c1b5a597f6926] o.p.t.controller.PersonController`. The event reached the appender fully correlated. |
| Frontend render race in `toolbar.js` | The backend never published the log at all. `LogCaptureIntegrationTest` uses no browser and fails identically. |

The instrumentation that settled it: printing the root logger's appender list at each registration
showed the first context's appender absent — with a *new* `ConsoleAppender` instance — the moment the
second context registered. A `LoggerContextListener` capturing a stack trace on `onReset`, and an
override of `PeekabootLogbackAppender.stop()`, named the three resets and their callers.

Note that a Logback status listener is useless here: `reset()` clears the status manager's listeners,
so the probe wipes itself out and reports nothing.

## The fix

`LogbackCaptureReinstaller`, an `ApplicationListener<ApplicationEnvironmentPreparedEvent>` registered
in `META-INF/spring.factories` so Spring Boot instantiates it for *every* application in the JVM, and
ordered `LoggingApplicationListener.DEFAULT_ORDER + 1` so it runs once logging has been initialised.
It re-attaches the appenders of all still-running contexts, tracked in a JVM-wide registry on
`LogbackAppenderRegistrar` (JVM-wide because the `LoggerContext` is).

Appenders must be **restarted**, not merely re-attached: the reset stops them, and
`AppenderBase.doAppend` silently discards everything while stopped.

Covered by `DevToolbarAutoConfigurationTest.logCaptureSurvivesLogbackReinitialisation`, which forces
the real `stop()` + `reset()` double tap and asserts on a *captured event* (attachment alone would
pass with a stopped appender), and by
`logbackCaptureReinstallerRunsForEveryApplicationAfterLoggingIsInitialised`, which pins the
registration and the ordering the repair depends on.

## Traps for anyone changing this again

- **Do not loosen `isTraceComplete` to also wait for logs.** It would hide a real backend failure.
- **Do not weaken, skip, or delete the log-dependent tests.** They are the only coverage of the
  feature and they were correctly detecting a real bug.
- **Do not validate a change on one OS alone**, and do not trust a green run in one class order —
  check `-Dsurefire.runOrder=alphabetical` and `reversealphabetical`, since the whole class of bug
  hides behind ordering.
- A test asserting only that the appender is *attached* is not enough; assert that an event is
  captured.

## Known unrelated flake

`TraceOverlayTest.queriesTabListsTheJdbcQueryFromThePersonsPage:359` failed once in a
`reversealphabetical` run (queries tab empty) and passed on re-run and on the pre-fix tree. Not
related to log capture; not investigated.

## Key file references

| Concern | Location |
|---|---|
| Repair hook | `peekaboot-spring-boot-autoconfigure/src/main/java/org/peekaboot/autoconfigure/LogbackCaptureReinstaller.java` |
| Appender registry / attach / detach | `peekaboot-spring-boot-autoconfigure/src/main/java/org/peekaboot/autoconfigure/DevToolbarAutoConfiguration.java` |
| MDC gate that drops uncorrelated events | `peekaboot-backend/src/main/java/org/peekaboot/backend/log/PeekabootLogbackAppender.java:46-51` |
| Toolbar trace id (different source than MDC) | `peekaboot-backend/src/main/java/org/peekaboot/backend/filter/DevToolbarFilter.java:132` |
| Headless discriminator | `peekaboot-testing-app/src/test/java/org/peekaboot/testingapp/integration/LogCaptureIntegrationTest.java` |
| The formerly failing browser tests | `peekaboot-testing-app/src/test/java/org/peekaboot/testingapp/ui/ToolbarTest.java:189`, `.../TraceOverlayTest.java:96` |
