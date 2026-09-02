# Improvements

Known gaps, open decisions, and the things that look like defects but are not.

Everything here was found deliberately — in review, in verification, or by tripping over it. Each
item says what it costs to leave alone. When one is closed it either leaves this file or moves to
§5 with the reasoning that closed it, so the record survives the work.

Sections 1 and 2 need a person. Section 3 is settled. §5 holds what earlier backlog items
turned into, with the reasoning.

---

## 1. Decisions waiting on a call

### 1.1 Request and response body capture — deferred, deliberately

**Deferred.** The unused domain fields and the `RequestCaptureFilter` call sites stay exactly as
they are, as the seam for a future improvement. Do not remove them as dead code, and do not
implement capture as a side effect of another task.

`RequestCaptureFilter.captureRequest` passes `null` for `requestBody` and `List.of()` for
`uploadedFiles` when it builds the `RequestCompletedEvent`. `HttpRequest.Body` and
`HttpRequest.UploadedFile` exist in the domain model; nothing populates them.

What *is* captured: method, path, masked query string, masked request headers, query and form
parameters, resolved controller class and method, response status, masked response headers,
duration. The documentation describes exactly that, so nothing currently overclaims.

**If you build it**, masking is the hard part, not capture: bodies carry credentials in shapes
`MaskingEngine`'s key-name rules cannot see, because there are no key names — a JSON body is
structure, a form post is pairs, a file upload is bytes. Sizing and truncation need a cap and the
`TRUNCATED` signal the UI already renders. It wants its own design pass.

### 1.2 Branch protection on `dev` is enforced, but admins bypass it

The `green-default-branch` ruleset is active and requires the `build-on-push` status check, but
its bypass list holds organisation admins and the repository admin role with `bypass_mode: always`,
so the owner's direct pushes never wait for the check — they report `Bypassed rule violations …
Required status check "build-on-push" is expected` and land anyway.

This is a GitHub repository setting, not code — it cannot be changed from a working copy. Either
remove the bypass actors, in which case work goes via PR, or remove the rule as misleading. A
public repository advertising a required check its owner bypasses looks worse than either.

---

## 2. Known gaps

### 2.1 Latent duplicate-span residue in `TraceDataBundle`, currently unreachable

Found while proving the read-time dedup pass removable: a harness drove the real
`TraceDataBundle` through all 14 arrival permutations of the relevant span shapes, and §5.1
summarises the result.

On a **triple**-nested mutually-duplicate chain `R←D←X`, arrival orders `R,X,D` and `X,R,D` leave a
duplicate: `TraceDataBundle.addSpan` returns early when `isDuplicateOfStoredParent` matches, without
running `absorbDuplicateChildrenOf`, so children of the folded span are never re-examined.

Unreachable for two independent reasons — either alone closes it:

1. **It needs a third nesting level.** Only two DataSource decorators exist
   (`peekaboot-testing-app/pom.xml`), and `SpanDuplicateMatcher.SERVICE_IDENTIFIER_KEYS`
   carries exactly one key per decorator.
2. **It needs a span to arrive after its own parent**, which `BatchSpanProcessor`'s FIFO end-order
   forbids.

The genuine-grandchild case (`G,R,D`) is harmless: `isDuplicate` is an equivalence relation, so only
a span duplicating the folded one can duplicate the survivor.

**Regression triggers — the reason this entry exists:** adding a third key to
`SERVICE_IDENTIFIER_KEYS`, or making `isDuplicate` non-transitive. If either happens, the fix is a
few lines in `TraceDataBundle` — **not** a second dedup implementation.

### 2.2 `ToolbarLateSpanIT` depends on timing it does not own

`LateSpanFixture.LateSpanController.LATE_WORK` is 1500ms, chosen against the toolbar's fetch ladder
(`toolbar.js`; the delays are documented in `peekaboot-frontend/README.md`) and the test profile's
50ms span export delay. The margins are in that field's Javadoc, including the point that the
ladder's clock starts when the ES module graph *executes*, not when the response is sent.

Not a defect — a dependency that cannot be expressed in code, because the ladder lives only in a
static JS file with no Java-accessible source of truth. **Changing the ladder, the export delay, or
`LATE_WORK` invalidates the arithmetic; redo it rather than assume it still holds.**

### 2.3 Counting tests: surefire under-reports `@Nested` classes

Surefire's per-class `.txt` summaries report `Tests run: 0` for `@Nested` classes and annotation
counts miss `@ParameterizedTest`; the counting recipe lives in [`TESTING.md`](TESTING.md).

---

## 3. Deliberately unfixed

| Item | Where | Why it stands |
|---|---|---|
| Three env vars mask that arguably needn't | `masking/MaskingRules.java` | `XDG_SESSION_ID`, `SSH_AUTH_SOCK`, `CREDENTIALS_DIRECTORY` are caught by the `session-id`, `auth` and `credential` rules. Those rules earn their keep elsewhere and these three are sensitive-adjacent. Revisit only if someone complains. |
| `ApplicationMapper.maskBuild`'s `: Collections.emptyMap()` branch is unreachable | `mapper/actuator/ApplicationMapper` | `TreeMasker.mask` always returns a `LinkedHashMap` for `Map` input. Kept because it keeps the `@SuppressWarnings("unchecked")` honest. |
| Test teardown tolerates `TargetClosedError` and `TimeoutError` | `ui/PlaywrightTestBase.closePage()` | Both log what they swallowed. See §5.6 before narrowing these to per-test `unroute` calls. |

---

## 4. Do not "fix" these

Each looks like a defect and is not. All checked against source.

- **`SELECT customer_order` in a span tree is correct.** It is OpenTelemetry's own span-name summary.
  The Spans tab renders `span.name` unconditionally and never consults `QueryExtractor`; the Queries
  tab renders extracted SQL. Two independent pipelines — `ARCHITECTURE.md`'s *Query Extraction*
  section has the detail, and `trace-detail-queries-*.png` shows the difference.
- **Off-local, every property masks — `server.port` and `os.name` included.** Actuator value
  visibility follows the launch context: `show-values: always` is emitted only on a local run, and is
  *absent* otherwise so Spring's own default applies. Emitting an explicit `never` would pin Spring's
  current default into applications not using Peekaboot at all.
- **`peekaboot.enable-unmasking` gates the reveal step only.** It is not the visibility switch.
- **The credential fixtures in `MaskingEngineTest` are split literals** (`"xoxb" + "-123…"`) because
  GitHub push protection blocks the contiguous form. There is a comment at each site. Do not tidy
  them together; write new provider fixtures the same way.
- **SQL masking is value-patterns only.** A credential with no provider-recognisable shape in an
  ordinary column is not caught. The security page says so and tells readers to assume traces carry
  plaintext SQL. Do not upgrade that caveat into a promise.
- **No entropy-based secret detection.** Deliberately rejected: entropy suits a scanner that flags
  candidates for a human, not a dashboard that would *destroy* the value on screen. Masking a git SHA
  or a base64 asset makes the tool worse at its only job.
- **Bare `key` is not a masking rule.** It would mask `spring.jpa.key-generator` and
  `server.ssl.key-store`. Compound names (`api-key`, `private-key`, `secret-key`) cover the real
  cases, and `key-store-password` is caught by `password`.
- **Scheduled-job detection is tags-only**, so Quartz, raw threads and a direct call to a
  `@Scheduled` method classify `INTERNAL` by design — see *Root Action Type* in
  [`GLOSSARY.md`](GLOSSARY.md).
- **A non-servlet application starts cleanly with Peekaboot on the classpath** (reproduced with
  `spring-webmvc` absent entirely); the servlet guard keeps it from registering beans nothing would
  call.
- **`ScreenshotCapture` clicking the reveal control is safe only under a narrow, deliberate scope -
  do not widen it.** It photographs a revealed `spring.datasource.password` on the Environment and
  Config tabs because that value is a placeholder already plaintext in the repository (`compose.yml`,
  `application-screenshots.yml`), so revealing it discloses nothing. That reasoning does not extend to
  any other group on those tabs, and never to `systemEnvironment` or `systemProperties`: those are
  read from whatever machine runs the capture and can carry real usernames, paths, hostnames or
  credentials that have no business in a public repository's history. If you extend this tool to
  reveal another group, first confirm every value in it - not just the one you're adding - originates
  in a file already committed to this repository, the same test `MASKED_GROUP_HEADER_SELECTOR` and
  `REVEAL_BUTTON_SELECTOR` already apply. When in doubt, don't click it.

---

## 5. Closed, with the reasoning that closed it

Kept because each conclusion cost real investigation and would otherwise be re-derived.

**5.1 The read-time `SpanDeduplicator` pass was removed.** Dedup happens at write time
(`SpanDuplicateMatcher` / `TraceDataBundle.addSpan`); the read-time pass duplicated it across two
packages. Removability was established by driving the real `TraceDataBundle` through all 14 arrival
permutations with a harness, not by inspection. The only orders that leak are unreachable — see §2.1,
which exists precisely so this is not quietly reinstated.

**5.2 Chained-redirect residue was fixed, not documented away.** `retargetChainedRedirects` re-points
chained entries at the surviving id, so `pruneRedirectsPointingAt` drops them all at eviction. The
class Javadoc's "stays bounded by the `maxSpans` cap" is now true of the code. A regression test runs
500 triples against a cap of 10 and asserts the table stays within `cap * 2`.

**5.3 `info.build` is masked.** It was the one actuator field on the read path skipping masking. Now
routed through a `TreeMasker` over the shared `MaskingEngine` bean — the idiom `ConfigMapper` and `HealthMapper` use —
with `unmask` threaded from `ActuatorInsightsService` like every other masking-aware mapper.

**5.4 The Mockito self-attach WARN is gone.** `peekaboot-testing-app` configures the `-javaagent` via
`maven-dependency-plugin`, matching the other two modules. The remaining `Sharing is only supported
for boot loader classes` line is a *different*, unavoidable JVM CDS notice, documented in
`TESTING.md`.

**5.5 The SpringDoc startup WARN is gone.** `SpringDocAppInitializer` warns when
`springdoc.api-docs.enabled` / `swagger-ui.enabled` are *unset*; both are
`@ConditionalOnProperty(… matchIfMissing = true)` everywhere they gate anything, so `true` is
indistinguishable from absent. Verified against 3.1.0 bytecode — exactly three callers of those flags
exist, all constructing the warning initialiser. Behaviourally a no-op. It lives in the sample app's
*main* resources because the WARN fires on every launch, not only under test.

**5.6 The `TraceOverlayIT` flake was root-caused.** The toolbar's fetch ladder polls
`/api/traces/{id}/insights` for up to 4.75s regardless of test lifetime. Three tests route and
never unroute — `TraceOverlayIT.closeButtonDismissesTheOverlayOnTheErrorPath`,
`ToolbarIT.toolbarShowsPendingWhenTheTraceRequestFails`, and
`ToolbarIT.openOverlayImportFailureIsCaughtAndLeavesTheBarUsable`, the last with a Javadoc
explaining it *must not* unroute because the browser's module map caches the failed import. So a
scheduled poll can fire during `context().close()`. The tolerance lives in `PlaywrightTestBase`
rather than per-test because the condition is structural, and because `unroute` is itself an
interception update over the same wire and would not close the race. Reproduced once in 7 runs
before; 0 in 14 after.

**5.7 Both screenshot gaps are closed.** `ScreenshotCapture` now clicks into the trace overlay's
Queries tab and expands a masked property group on Environment and Config. `trace-detail-queries-*`
shows real Hibernate SQL — column lists, aliases, lower-case — which no shipped image had ever shown.
`dashboard-environment-*` and `dashboard-config-*` show `spring.datasource.password` masked beside
visible values, demonstrating *selective* masking rather than the blanket kind. The screenshots
profile needed `enable-unmasking` and `show-values: always` for that: a JUnit-launched process is
never a local run, so without them Spring's own `Sanitizer` masks everything and the image would
prove the wrong thing.

**5.8 The trace list's `summary.logs` is populated.** `TraceInsightsService.getInsights()`
never ran the enrichment the single-trace path does, so every list entry kept
`TraceTreeMapper`'s `0/0/0` placeholder and the Traces tab never showed a log badge. The
counts are now taken in `mapBundle()` from the logs the bundle already carries — no extra
lookups, since `TraceStore.getTraces` hands the bundle over with its logs — through the same
`withLogsSummary`/`logsSummary` the detail path uses, so the two views cannot disagree.
