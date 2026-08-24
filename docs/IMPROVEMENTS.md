# Improvements

Known gaps, open decisions, and the things that look like defects but are not.

Everything here was found deliberately — in review, in verification, or by tripping over it. Each
item says what it costs to leave alone. When one is closed it either leaves this file or moves to
§5 with the reasoning that closed it, so the record survives the work.

The backlog this file opened with was worked through on 2026-08-24; §5 records what came of it.

Sections 1 and 2 need a person. Section 3 is settled.

---

## 1. Decisions waiting on a call

### 1.1 Request and response body capture — deferred, deliberately

**Decided 2026-08-24: not now.** The unused domain fields and the `RequestCaptureFilter` call sites
stay exactly as they are, as the seam for a future improvement. Do not remove them as dead code, and
do not implement capture as a side effect of another task.

`peekaboot-backend/src/main/java/org/peekaboot/backend/filter/RequestCaptureFilter.java:138,144`
passes `null` for `requestBody` and `List.of()` for `uploadedFiles`, both marked "not captured yet".
`HttpRequest.Body` and `HttpRequest.UploadedFile` exist in the domain model; nothing populates them.

What *is* captured: method, path, masked query string, masked request headers, query and form
parameters, resolved controller class and method, response status, masked response headers,
duration. The documentation describes exactly that, so nothing currently overclaims.

**If you build it**, masking is the hard part, not capture: bodies carry credentials in shapes
`MaskingEngine`'s key-name rules cannot see, because there are no key names — a JSON body is
structure, a form post is pairs, a file upload is bytes. Sizing and truncation need a cap and the
`TRUNCATED` signal the UI already renders. It wants its own design pass.

### 1.2 Branch protection on `dev` is not enforcing

Pushes report `Bypassed rule violations … Required status check "build-on-push" is expected`.

This is a GitHub repository setting, not code — it cannot be changed from a working copy. Either
make the required check block, in which case work goes via PR, or remove the rule as misleading.
Leaving it advertises a gate the branch does not have.

---

## 2. Known gaps

### 2.1 Latent duplicate-span residue in `TraceDataBundle`, currently unreachable

Found while proving the read-time dedup pass removable (§5.1). Full analysis, including the
harness output for all 14 arrival permutations:
[`analysis/span-dedup-arrival-orders.md`](analysis/span-dedup-arrival-orders.md).

On a **triple**-nested mutually-duplicate chain `R←D←X`, arrival orders `R,X,D` and `X,R,D` leave a
duplicate: `isDuplicateOfStoredParent` (`TraceDataBundle.java:91-99`) returns early without running
`absorbDuplicateChildrenOf`, so children of the folded span are never re-examined.

Unreachable today for two independent reasons — either alone closes it:

1. **It needs a third nesting level.** Only two DataSource decorators exist
   (`peekaboot-testing-app/pom.xml`), and `SERVICE_IDENTIFIER_KEYS`
   (`SpanDuplicateMatcher.java:15`) carries exactly one key per decorator.
2. **It needs a span to arrive after its own parent**, which `BatchSpanProcessor`'s FIFO end-order
   forbids.

The genuine-grandchild case (`G,R,D`) is harmless: `isDuplicate` is an equivalence relation, so only
a span duplicating the folded one can duplicate the survivor.

**Regression triggers — the reason this entry exists:** adding a third key to
`SERVICE_IDENTIFIER_KEYS`, or making `isDuplicate` non-transitive. If either happens, the fix is a
few lines in `TraceDataBundle` — **not** a second dedup implementation.

### 2.2 `ToolbarLateSpanTest` depends on timing it does not own

`LateSpanFixture.LateSpanController.LATE_WORK` is 1500ms, chosen against the toolbar's fetch ladder
(`toolbar.js`, cumulative 250ms / 750ms / 1750ms / 4750ms) and the test profile's 50ms span export
delay. The margins are in that field's Javadoc, including the point that the ladder's clock starts
when the ES module graph *executes*, not when the response is sent.

Not a defect — a dependency that cannot be expressed in code, because the ladder lives only in a
static JS file with no Java-accessible source of truth. **Changing the ladder, the export delay, or
`LATE_WORK` invalidates the arithmetic; redo it rather than assume it still holds.**

### 2.3 Counting tests: surefire under-reports `@Nested` classes

For a class using `@Nested`, surefire's per-class `.txt` summary reports `Tests run: 0` while the XML
carries the real total. In `peekaboot-backend`, `PeekabootControllerTest` and `MaskingEngineTest`
both report 0 in `.txt`. Counting `@Test` annotations instead is also wrong — it misses
`@ParameterizedTest` entirely, and `MaskingEngineTest` alone has 7 that expand to 107 invocations.

**Count from the XML, or from the reactor summary. Never from the `.txt` files.**

```bash
for f in <module>/target/surefire-reports/TEST-*.xml; do
  grep -m1 -o 'tests="[0-9]*"' "$f" | grep -o '[0-9]*'
done | paste -sd+ | bc
```

Current: backend 649, autoconfigure 90, testing-app 203 — **942**.

---

## 3. Deliberately unfixed

| Item | Where | Why it stands |
|---|---|---|
| Three env vars mask that arguably needn't | `masking/MaskingRules.java` | `XDG_SESSION_ID`, `SSH_AUTH_SOCK`, `CREDENTIALS_DIRECTORY` are caught by the `session-id`, `auth` and `credential` rules. Those rules earn their keep elsewhere and these three are sensitive-adjacent. Revisit only if someone complains. |
| `concepts.md`'s root-action-type priority table stayed on the site | `peekaboot-org.github.io/docs/concepts.md` | Structurally similar to material relocated into `ARCHITECTURE.md`, but it explains an icon the reader is looking at. Revisit only if the page grows. |
| `ApplicationMapper.maskBuild`'s `: Collections.emptyMap()` branch is unreachable | `mapper/actuator/ApplicationMapper` | `TreeMasker.mask` always returns a `LinkedHashMap` for `Map` input. Kept because it keeps the `@SuppressWarnings("unchecked")` honest. |
| Test teardown tolerates `TargetClosedError` and `TimeoutError` | `ui/PlaywrightTestBase.closePage()` | Both log what they swallowed. See §5.6 before narrowing these to per-test `unroute` calls. |

---

## 4. Do not "fix" these

Each looks like a defect and is not. All checked against source.

- **`SELECT customer_order` in a span tree is correct.** It is OpenTelemetry's own span-name summary.
  The Spans tab renders `span.name` unconditionally and never consults `QueryExtractor`; the Queries
  tab renders extracted SQL. Two independent pipelines — `ARCHITECTURE.md`'s *Query Extraction*
  section has the detail, and `trace-detail-queries-*.png` now shows the difference.
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
- **Scheduled-job detection is tags-only**, keyed on Spring's `code.function`/`code.namespace`.
  Quartz and raw threads are not recognised, and a *direct* call to a `@Scheduled` method that also
  carries `@Observed` classifies `INTERNAL`. Both intentional and documented.
- **A non-servlet application does not crash.** Reproduced twice, including with `spring-webmvc`
  absent entirely. The old behaviour was silently registering dead beans; the guard prevents that.
  Any crash claim you find is stale.
- **`ScreenshotCapture` never clicks the reveal control.** The masked-value screenshots show `******`
  beside visible values, with the "Show secrets" control present but unused. Photographing a revealed
  state would publish fixture-shaped credentials into a public repository's history. If a
  before/after contrast ever seems worth it, that is a human decision, not a tooling gap.

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
routed through `TreeMasker(new MaskingEngine())` — the idiom `ConfigMapper` and `HealthMapper` use —
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

**5.6 The `TraceOverlayTest` flake was root-caused.** The toolbar's fetch ladder polls
`/api/traces/{id}/insights` for up to 4.75s regardless of test lifetime. Three tests route that
endpoint and never unroute — `TraceOverlayTest:222`, `ToolbarTest:207`, and `ToolbarTest:257`, the
last with a Javadoc explaining it *must not* unroute because the browser's module map caches the
failed import. So a scheduled poll can fire during `context().close()`. The tolerance lives in
`PlaywrightTestBase` rather than per-test because the condition is structural, and because `unroute`
is itself an interception update over the same wire and would not close the race. Reproduced once in
7 runs before; 0 in 14 after.

**5.7 Both screenshot gaps are closed.** `ScreenshotCapture` now clicks into the trace overlay's
Queries tab and expands a masked property group on Environment and Config. `trace-detail-queries-*`
shows real Hibernate SQL — column lists, aliases, lower-case — which no shipped image had ever shown.
`dashboard-environment-*` and `dashboard-config-*` show `spring.datasource.password` masked beside
visible values, demonstrating *selective* masking rather than the blanket kind. The screenshots
profile needed `enable-unmasking` and `show-values: always` for that: a JUnit-launched process is
never a local run, so without them Spring's own `Sanitizer` masks everything and the image would
prove the wrong thing.

**5.8 Hygiene.** `ActuatorInsightsService`'s field is no
longer named `rawService`; the parser fixture is no longer `sample_actuator_all_raw.json`;
`findDbSystem`'s comment matches `findSql`'s standard; `DEV_TOOLBAR_PROPERTY_SOURCE_NAME` is grouped
with its peers; `tracing.md` no longer cross-references the dev-toolbar page twice in one paragraph.
