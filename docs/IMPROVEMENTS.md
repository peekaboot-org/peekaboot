# Improvements

Known gaps, deferred findings and open decisions, as of the toolbar-defaults and API-trim work
(branch `feat/toolbar-defaults-and-api-trim`, 2026-08-24).

Everything here was found deliberately — in review, in verification, or by tripping over it — and
judged non-blocking at the time. Nothing here is a surprise waiting to happen; each item says what
it costs to leave alone.

Sections 1 and 2 need a person. Sections 3 onward are work someone can just pick up.

---

## 1. Decisions waiting on a call

### 1.1 Should the dev toolbar capture request and response bodies?

`peekaboot-backend/src/main/java/org/peekaboot/backend/filter/RequestCaptureFilter.java:138,144`

`captureRequest` passes `null` for `requestBody` and `List.of()` for `uploadedFiles`, both marked
"not captured yet". `HttpRequest.Body` and `HttpRequest.UploadedFile` exist in the domain model and
nothing ever populates them — someone intended this and stopped.

What *is* captured: method, path, masked query string, masked request headers, query and form
parameters, resolved controller class and method, response status, masked response headers,
duration.

The documentation overclaimed this in six places and has been corrected, so nothing currently lies
about it. The open question is whether to build it.

**If you do**, masking is the hard part, not capture: bodies carry credentials in shapes
`MaskingEngine`'s key-name rules cannot see, because there are no key names — a JSON body is
structure, a form post is pairs, a file upload is bytes. Sizing and truncation need a cap and a
`TRUNCATED` signal the UI already knows how to render.

**Do not implement this silently as a side effect of another task.** It changes what Peekaboot
holds in memory and what the toolbar puts on screen.

### 1.2 Does the read-time `SpanDeduplicator` pass still earn its keep?

`peekaboot-backend/src/main/java/org/peekaboot/backend/service/TraceInsightsService.java:119-122,161`

Deduplication moved to write time, so this read-time pass should be a no-op. It used to be
described as an asymmetry between `/insights` and `/raw`; `/raw` is gone, so that framing no longer
applies and there is only one read path.

Two honest options: drop it, or keep it and document in its Javadoc that it is a safety net for the
one span-arrival order write-time dedup does not collapse. What is not fine is leaving duplicated
logic across two packages with no stated reason.

*Small either way — the decision is the work.*

---

## 2. Coverage the tooling cannot currently reach

### 2.1 No screenshot demonstrates query extraction

`peekaboot-testing-app/src/test/java/org/peekaboot/testingapp/ui/ScreenshotCapture.java`

The trace-detail overlay opens on the **Spans** tab and `ScreenshotCapture` never clicks across to
**Queries**. Those are two independent rendering paths — Spans renders `span.name`, Queries renders
the SQL `QueryExtractor` extracts (see `docs/ARCHITECTURE.md`, *Query Extraction*) — so no shipped
image has ever shown `QueryExtractor`'s output, working or broken.

This mattered: an earlier handover read `SELECT customer_order` in a span tree as evidence of a
bug. It was not. That is OpenTelemetry's own span-name summary and is correct there.

**Remedy:** extend the capture tool to open the Queries tab and add that image to the website. Only
needed if the site should illustrate query extraction specifically.

### 2.2 Environment and Config screenshots do not demonstrate masking

Same file. The tool never expands property groups and never clicks the reveal control, so those
images show collapsed groups — not masked values, not the "Show secrets" toggle. The two pages
where masking matters most describe behaviour their pictures do not show.

*Medium: the capture tool needs to drive interactions, not just navigate.*

### 2.3 Surefire under-reports `@Nested` classes in its `.txt` summaries

Not a product bug — a measurement trap that produced two wrong test counts during this work, in
opposite directions.

For a class using `@Nested`, surefire's per-class `.txt` summary reports `Tests run: 0` while the
XML carries the real total. In `peekaboot-backend`, `PeekabootControllerTest` (26) and
`MaskingEngineTest` (107) both report 0 in `.txt`. Summing `.txt` gives 525; summing the XML gives
the true 658.

Counting `@Test` annotations instead is also wrong: it misses `@ParameterizedTest` entirely —
`MaskingEngineTest` alone has 7 that expand to 107 invocations.

**Count from the XML, or from the reactor summary. Never from the `.txt` files.**

```bash
for f in <module>/target/surefire-reports/TEST-*.xml; do
  grep -m1 -o 'tests="[0-9]*"' "$f" | grep -o '[0-9]*'
done | paste -sd+ | bc
```

Current true counts: backend 658, autoconfigure 90, testing-app 203 — **951**.

### 2.4 Two known flake windows in the browser tests

- `TraceOverlayTest` — a Playwright `TargetClosedError` was observed once in an otherwise green
  `mvn verify`. Pre-existing; this work never touched that test. Not yet characterised.
- `ToolbarLateSpanTest` — depends implicitly on the toolbar's first render landing before the
  fixture's 800 ms late span ends. With a 50 ms test export delay and a 250 ms first fetch there is
  comfortable margin, but the dependency is not asserted. Under heavy CI load it could invert, and
  the failure would look like a product bug rather than a timing one.

*Small: state the assumption in the test, or make the fixture's delay derive from the ladder.*

---

## 3. Low-risk correctness nits

Each was reviewed and judged not worth blocking on. All still true.

| Item | Where | Cost of leaving it |
|---|---|---|
| Chained-redirect residue | `TraceDataBundle.pruneRedirectsPointingAt` | `redirectsPointingAt[evicted]` is dropped, but an entry keyed on a *folded-away* id is never removed, so `gc → dup → survivor` leaves residue once `survivor` is evicted. Needs triple instrumentation, which does not occur in this domain — but the class Javadoc's "stays bounded by the maxSpans cap" is slightly stronger than the code. |
| `info.build` is not masked | `mapper/actuator/ApplicationMapper` | A free-form `Map<String,Object>` of consumer-supplied build info reaches the insights response unmasked. Low-risk carrier; noted for completeness, not because a leak is likely. |
| Three env vars mask that arguably needn't | `masking/MaskingRules.java` | `XDG_SESSION_ID`, `SSH_AUTH_SOCK`, `CREDENTIALS_DIRECTORY` are caught by the `session-id`, `auth` and `credential` rules. Deliberate — those rules earn their keep elsewhere and these three are sensitive-adjacent. Revisit only if someone complains. |

---

## 4. Code and doc hygiene

Cosmetic. Fold into the next change that touches the file rather than making a pass of its own.

- `ActuatorInsightsService` still names its collaborator field `rawService` after the
  `ActuatorRawMapper` → `ActuatorResponseParser` rename.
- `PeekabootDefaultsEnvironmentPostProcessor`'s `DEV_TOOLBAR_PROPERTY_SOURCE_NAME` sits between the
  property constants and the resource constants rather than grouped with either.
- `QueryExtractor.findDbSystem`'s new branch comment is terser than `findSql`'s equivalent, which
  explains *why* the current convention is ordered ahead of the superseded one.
- `peekaboot-backend/src/test/resources/.../sample_actuator_all_raw.json` kept its `_raw` name after
  the package rename; it is the parser test's fixture.
- `peekaboot-org.github.io/docs/concepts.md`'s root-action-type priority table is structurally
  similar to material relocated into `ARCHITECTURE.md`, but it explains an icon the reader is
  looking at, so it stayed. Revisit only if the page grows.
- `peekaboot-org.github.io/docs/tracing.md` cross-references the dev-toolbar page twice inside one
  two-sentence paragraph.

---

## 5. Build and repository hygiene

- **`peekaboot-testing-app` never got the Mockito static `-javaagent` surefire fix.**
  `peekaboot-backend` and `peekaboot-spring-boot-autoconfigure` both configure it via
  `maven-dependency-plugin`; this module does not, so the inline-mock-maker self-attach WARN still
  prints. One-line pom change. Recorded as accepted noise in `docs/TESTING.md`.
- **SpringDoc enabled-by-default WARNs** print once per context start in `peekaboot-testing-app`
  for `/v3/api-docs` and `/swagger-ui.html`. Never investigated; may just need a property.
- **Branch protection on `dev` is not enforcing.** Pushes report
  `Bypassed rule violations … Required status check "build-on-push" is expected`. Either make the
  rule block — in which case work goes via PR — or remove it as misleading.

---

## 6. Do not "fix" these

Each looks like a defect and is not. All were checked against source.

- **`SELECT customer_order` in a span tree is correct.** It is OpenTelemetry's own span-name
  summary. The Spans tab renders `span.name` unconditionally and never consults `QueryExtractor`.
- **Off-local, every property masks — `server.port` and `os.name` included.** Actuator value
  visibility follows the launch context: `show-values: always` is emitted only on a local run, and
  is *absent* otherwise so Spring's own default applies. Emitting an explicit `never` would pin
  Spring's current default into applications that are not using Peekaboot at all.
- **`peekaboot.enable-unmasking` gates the reveal step only.** It is not the visibility switch.
- **The credential fixtures in `MaskingEngineTest` are split literals** (`"xoxb" + "-123…"`) because
  GitHub push protection blocks the contiguous form. There is a comment at each site. Do not tidy
  them back together; write new provider fixtures the same way.
- **No entropy-based secret detection.** Deliberately rejected: entropy suits a scanner that flags
  candidates for a human, not a dashboard that would *destroy* the value on screen. Masking a git
  SHA or a base64 asset makes the tool worse at its only job.
- **Bare `key` is not a masking rule.** It would mask `spring.jpa.key-generator` and
  `server.ssl.key-store`. Compound names (`api-key`, `private-key`, `secret-key`) cover the real
  cases, and `key-store-password` is caught by `password`.
- **Scheduled-job detection is tags-only**, keyed on Spring's `code.function`/`code.namespace`.
  Quartz and raw threads are not recognised, and a *direct* call to a `@Scheduled` method that also
  carries `@Observed` classifies `INTERNAL`. Both consequences are intentional and documented.
- **A non-servlet application does not crash.** Reproduced twice, including with `spring-webmvc`
  absent entirely. The old behaviour was silently registering dead beans; the guard prevents that.
  Any crash claim you find is stale.
