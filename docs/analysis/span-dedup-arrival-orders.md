# Why the read-time span-dedup pass was deleted (and when to bring it back)

Subject: commit `dd61bc0 refactor: drop the redundant read-time span-dedup pass`
Reviewed: 2026-08-24, independently, against `feat/toolbar-defaults-and-api-trim` @ `71ea23e`.

**Verdict: the deletion was CORRECT.** Keep it deleted. There is exactly one span
arrival order that defeats write-time dedup and that the read-time pass would have
caught, and it is unreachable in this codebase for two independent reasons. Both
reasons are things a future change can break, so section 7 lists what to watch.

---

## 1. What `dd61bc0` removed

- `peekaboot-backend/src/main/java/org/peekaboot/backend/mapper/trace/SpanDeduplicator.java` (86 lines) and its 260-line test.
- Its two call sites in `TraceInsightsService` — now
  `peekaboot-backend/src/main/java/org/peekaboot/backend/service/TraceInsightsService.java:125`
  (trace list) and `:165` (single trace), both of which now call
  `TraceData.fromSpans(bundle.traceId(), bundle.spans())` directly.

The deleted pass (recoverable via
`git show dd61bc0^:peekaboot-backend/src/main/java/org/peekaboot/backend/mapper/trace/SpanDeduplicator.java`)
did exactly one thing: a single non-iterated sweep over the already-complete span
list, removing any span `s` whose `parentId` resolved to a span `p` *present in the
same list* with `SpanDuplicateMatcher.isDuplicate(s, p)`, then re-parenting orphans
onto the nearest surviving ancestor. It used the **same** matcher as the write path
(`SpanDuplicateMatcher.isDuplicate`), and it only ever compared a span against its
own parent — never siblings, never grandparents, never a fixpoint loop. So the
question is purely: *can a span survive `TraceDataBundle.addSpan` while its resolved
parent is also stored and duplicates it?*

Note that the read pass was never a universal net anyway: `InMemoryTraceStore`'s own
bucket classification (`InMemoryTraceStore.java:181` `hasError`, `:186` `isSlow`)
always read `bundle.spans()` raw, bypassing it.

## 2. What the write path considers a duplicate

`SpanDuplicateMatcher.isDuplicate(a, b)`
(`peekaboot-backend/src/main/java/org/peekaboot/backend/tracing/store/SpanDuplicateMatcher.java:22-24`):
`a.name().equals(b.name())` **and** the tag maps are equal after removing
`peer.service` and `jdbc.datasource.name` (`SpanDuplicateMatcher.java:15`).

Two properties of this definition carry the whole analysis below:

- **It is an equivalence relation** — reflexive, symmetric, transitive — because it
  is equality of a projection (name + tags-minus-two-keys). Therefore if `X dup D`
  and `D dup R`, then `X dup R`. Section 5 depends on this.
- **It is structure-free**: it never looks at ids, times, or parentage. Parentage is
  supplied entirely by the caller.

## 3. What the write path does, precisely

`TraceDataBundle.addSpan(span, maxSpans)`
(`peekaboot-backend/src/main/java/org/peekaboot/backend/tracing/store/TraceDataBundle.java:72-84`),
entirely under `spansLock`:

1. **Fold-into-parent** (`:91-99`). Resolve `span.parentId()` through the redirect
   chain (`resolve`, `:159-165`). If that span is stored and `isDuplicate(span,
   parent)` — record a redirect `span → parent` (`:136-140`) and **return without
   storing**. *No further work happens on this path.* ← this early return is the
   only crack in the armour; see section 5.
2. **Store** (`:101-108`). Index the span under its **raw** (unresolved) `parentId`
   in `childrenByParentId` (`:103-107`).
3. **Absorb duplicate children** (`:116-131`). Look up `childrenByParentId[span.spanId()]`
   — spans that arrived *earlier* naming this span as parent — and fold away any
   that duplicate it, recording redirects.
4. **Cap and evict** (`:79-82`, `:167-176`), pruning now-dead redirects (`:181-186`).

Reads go through `spans()` (`:234-244`), which rewrites each stored span's
`parentId` through `resolve` (`withResolvedParent`, `:248-254`) and sorts by
`creationOrder`. So a read *already* sees redirect-resolved parentage; the deleted
pass operated on resolved ids too. Chained folds (`gc → dup → survivor`) are
flattened by `retargetChainedRedirects` (`:148-155`), added in the follow-up commit
`320bf39`.

## 4. The assumption, and where it was verified

> **Assumption A** — spans reach `TraceDataBundle.addSpan` one at a time, and for a
> causally nested pair the child arrives before the parent.

Verified path: `OtelSpanExporter.export`
(`peekaboot-backend/src/main/java/org/peekaboot/backend/tracing/bridge/otel/OtelSpanExporter.java:41-50`)
publishes one `SpanDataEvent` per span, in batch iteration order →
`TraceStoreEventListener.onSpanData`
(`peekaboot-backend/src/main/java/org/peekaboot/backend/tracing/store/TraceStoreEventListener.java:21-26`,
a plain synchronous `@EventListener`) → `InMemoryTraceStore.addSpan`
(`InMemoryTraceStore.java:84-88`) → `TraceDataBundle.addSpan`. The exporter is
registered as a bare `SpanExporter` bean
(`peekaboot-spring-boot-autoconfigure/src/main/java/org/peekaboot/autoconfigure/OtelTracingAutoConfiguration.java:22-26`),
so Spring Boot wraps it in OTel's `BatchSpanProcessor`, whose queue is FIFO in
`onEnd` order. A nested child ends before the ancestor containing it, so it exports
first. `creationOrder` is stamped at export time (`OtelSpanExporter.java:129` →
`InMemoryTraceStore.nextCreationOrder`, `:79-81`), i.e. it records *arrival* order,
not end order.

**The analysis below does not rely on Assumption A.** The write path deliberately
handles both directions (`TraceDataBundleTest.java:59-62` covers the parent-first
ordering "for robustness, not because it's expected in production"), and I enumerated
all orderings regardless. Assumption A is only invoked as a *second*, independent
reason the one surviving gap is unreachable.

> **Assumption B** — a JDBC call produces exactly **two** nested spans, never three.

Verified: the duplication comes from two independent DataSource-decorating stacks
both present in `peekaboot-testing-app/pom.xml` — `net.ttddyy.observation`
datasource-micrometer (`:66`, `:71`), which tags `jdbc.datasource.name`, and
`com.github.gavlyukovskiy` datasource-proxy (`:82`), which tags `peer.service`.
`SERVICE_IDENTIFIER_KEYS` (`SpanDuplicateMatcher.java:15`) contains exactly those two
keys, one per stack. A third nesting level would need a third decorator, and
`TraceDataBundle.java:143-144` already states in-tree that triple nesting "does not
occur in production".

## 5. Arrival-order enumeration

Notation: `R` = real span; `D` = its double-instrumented duplicate, `D.parent = R`;
`G` = a genuine grandchild (e.g. a `result-set` span), `G.parent = D`, **not** a
duplicate of anything; `X` = a third mutually-duplicate span, `X.parent = D`
(triple nest). "Leaks" = after `addSpan` of the whole sequence, `spans()` still
contains a span whose resolved parent is present and duplicates it — i.e. exactly
what the deleted read pass would have removed.

Every row below was machine-checked against the real `TraceDataBundle` (harness in
the appendix), not reasoned about only on paper.

| # | Shape | Arrival order | What the write path does | Leaks? |
|---|---|---|---|---|
| 1 | `R ← D` | `R, D` | step 1 folds `D` into stored parent `R` | no |
| 2 | `R ← D` | `D, R` | `D` stored (parent absent); `R` stored, step 3 absorbs `D` | no |
| 3 | `R ← D ← G` | `D, G, R` | `D` stored; `G` stored (not a dup of `D`); `R` absorbs `D`; `G` resolves to `R` | no |
| 4 | `R ← D ← G` | `D, R, G` | `R` absorbs `D`; `G` arrives, `resolve(D) = R`, `G` not a dup of `R` → stored | no |
| 5 | `R ← D ← G` | `G, D, R` | the *expected* production order — `TraceDataBundleTest.java:74` pins it | no |
| 6 | `R ← D ← G` | `G, R, D` | `G` stored under raw parent `D`; `R` stored (no children indexed yet); `D` folds via step 1 and **returns early**, so step 3 never runs for `D` — but `G` is not a dup of `R`, so nothing leaks | no |
| 7 | `R ← D ← G` | `R, D, G` / `R, G, D` | as 4 / 6 | no |
| 8 | `R ← D ← X` (triple) | `X, D, R` | `D` absorbs `X`, `R` absorbs `D`, `retargetChainedRedirects` flattens `X → R` | no |
| 9 | `R ← D ← X` | `D, X, R` | `X` folds into stored `D`; `R` absorbs `D`; chain flattened | no |
| 10 | `R ← D ← X` | `D, R, X` | `R` absorbs `D`; `X` arrives, `resolve(D) = R`, folds into `R` | no |
| 11 | `R ← D ← X` | `R, D, X` | `D` folds into `R`; `X` resolves to `R`, folds | no |
| 12 | `R ← D ← X` | **`R, X, D`** | `X` stored under raw parent `D`; `D` folds via step 1 and returns early — **step 3 is skipped for `D`**, so `X` is never re-examined. `X` remains stored, resolving to `R`, and `X dup R`. | **YES** |
| 13 | `R ← D ← X` | **`X, R, D`** | same mechanism as 12 | **YES** |
| 14 | eviction | `R, filler, filler, D` with `maxSpans = 2` | `R` evicted before `D` arrives; `D` stored as an orphan (`resolve(R)` finds nothing). The read pass behaved identically — it also skipped spans whose parent was absent from the list. | no (and no difference) |

### Why rows 12 and 13 do not matter

They require **all three** of:

- **a triple nest** — three spans, pairwise `isDuplicate`, nested `R ← D ← X`.
  Excluded by Assumption B: exactly two DataSource decorators exist, and
  `SERVICE_IDENTIFIER_KEYS` enumerates exactly their two keys. A hypothetical third
  decorator would emit a *third* key that is **not** in the filter set, so its span's
  filtered tag map would differ and `isDuplicate` would return false — the read-time
  pass would not have caught it either. Triple nesting only becomes *expressible* if
  someone adds a key to `SERVICE_IDENTIFIER_KEYS`.
- **the middle span `D` arriving last**, after its own parent `R`. Excluded by
  Assumption A: `D` ends strictly before `R` does, so it exports first.
- and the leak is confined to children of `D`, which by transitivity of `isDuplicate`
  (section 2) can only leak if they duplicate `D` — i.e. the triple-nest condition
  again. This is why row 6, the same skipped-step-3 mechanism with a *genuine*
  grandchild, is harmless: `G` is not a duplicate of anything.

Two independent, separately-documented impossibilities. Reinstating an 86-line class
and a 260-line test in a second package to guard a shape that cannot be built with a
matcher that would not recognise it anyway is not a safety net; it is duplicated
logic with no stated reason. **The deletion was correct.**

## 6. Orders considered and found irrelevant

- **Non-adjacent duplicates.** Both the write path and the deleted read pass only
  ever compare a span to its (resolved) parent. Distance in the arrival sequence
  changes nothing: the write path's two directions (step 1 and step 3) between them
  cover "parent already here" and "parent arrives later" regardless of how many
  unrelated spans land in between.
- **Sibling duplicates / two root spans that duplicate each other.** Neither
  implementation touches these — the deleted test
  `deduplicate_shouldHandleSpansWithoutParentInTrace` asserted precisely that they
  are kept. No behaviour change.
- **Duplicate arriving after its twin was evicted** (row 14). Both implementations
  skip a span whose parent is not present. Identical outcome.
- **Redirect chains `gc → dup → survivor`.** Flattened at fold time
  (`TraceDataBundle.java:148-155`) and re-resolved on every read
  (`:159-165`, `:248-254`). Pinned by `TraceDataBundleTest.java:156`.
- **Concurrency.** `addSpan` holds `spansLock` for the entire fold
  (`:73-83`) and `spans()` copies under the same lock (`:235-243`), so concurrent
  writers can only produce a *different linear arrival order* — which is the space
  already enumerated above. No interleaving can produce a state no serial order can.

## 7. What would have to change for this conclusion to stop holding

Re-open this document if any of the following happens. The first three make row 12/13
reachable; the fourth invalidates the reasoning wholesale.

1. **A key is added to `SERVICE_IDENTIFIER_KEYS`
   (`SpanDuplicateMatcher.java:15`).** This is the single highest-signal trigger. The
   set has exactly one entry per DataSource decorator on the classpath; a third entry
   means a third nesting level of mutually-duplicate spans becomes expressible, which
   is precisely the triple-nest precondition.
2. **Anything asynchronous or reordering is inserted between the OTel exporter and
   the store** — an `@Async`/executor-backed `ApplicationEventMulticaster`, a queue
   in `TraceStoreEventListener`, a `SimpleSpanProcessor` swap, or an OTLP *receive*
   endpoint that ingests spans from another process. Any of these breaks Assumption A
   and makes "parent arrives before child" routine rather than exotic.
3. **A third DataSource-decorating instrumentation is added** to the supported stack
   (`peekaboot-testing-app/pom.xml:66,71,82` is the current inventory) *and* its
   service-identifier key is registered per (1).
4. **`SpanDuplicateMatcher.isDuplicate` stops being an equivalence relation.** If it
   becomes asymmetric or non-transitive — subset matching ("`a`'s tags are contained
   in `b`'s"), fuzzy name matching, a time-window heuristic, kind-aware rules — then
   the transitivity argument in section 5 collapses and duplicates could leak from
   ordinary two-level nesting, not just triple nests. **Re-run the enumeration from
   scratch if this method's contract changes at all.**

**If it does become reachable, the fix is not to reinstate the read pass.** The bug
is localised: `isDuplicateOfStoredParent` (`TraceDataBundle.java:91-99`) returns
without running `absorbDuplicateChildrenOf` for the span it just folded away. The
targeted repair is to re-examine the folded span's already-stored children against
the survivor on that path too — a few lines in the class that already owns the
concern, not a second dedup implementation in `mapper.trace`.

## 8. Reproducing the check

The harness below drives the real `TraceDataBundle` through every ordering in the
table and re-applies the deleted `SpanDeduplicator`'s matching logic to the result,
flagging anything it would still remove. It needs only `peekaboot-backend/target/classes`
and the `micrometer-tracing` jar. Rows 12 and 13 are the only two it flags.

```java
// DedupProbe.java — run with:
//   CP=peekaboot-backend/target/classes:~/.m2/repository/io/micrometer/micrometer-tracing/1.7.0/micrometer-tracing-1.7.0.jar
//   javac -cp "$CP" -d . DedupProbe.java && java -cp "$CP:." DedupProbe
import java.time.Duration; import java.time.Instant; import java.util.*;
import org.peekaboot.backend.tracing.store.*;

public class DedupProbe {
    static long order = 0;

    static SpanData span(String id, String parent, String name, String query, String peerService) {
        Instant start = Instant.EPOCH.plusMillis(++order * 100);
        Map<String, String> tags = new HashMap<>();
        tags.put("jdbc.query[0]", query);
        tags.put("peer.service", peerService);
        return new SpanData("t1", id, parent, name, null, start, start.plusMillis(50),
                Duration.ofMillis(50), tags, List.of(), null, null, null, null, null, List.of(), order);
    }

    /** Verbatim re-implementation of the deleted SpanDeduplicator.findDuplicateSpanIds. */
    static Set<String> readTimeWouldRemove(List<SpanData> spans) {
        Map<String, SpanData> byId = new HashMap<>();
        for (SpanData s : spans) byId.put(s.spanId(), s);
        Set<String> removed = new HashSet<>();
        for (SpanData s : spans) {
            if (s.parentId() == null || removed.contains(s.spanId())) continue;
            SpanData p = byId.get(s.parentId());
            if (p != null && SpanDuplicateMatcher.isDuplicate(s, p)) removed.add(s.spanId());
        }
        return removed;
    }

    static void run(String label, List<SpanData> arrivals, int cap) {
        TraceDataBundle b = new TraceDataBundle("t1");
        for (SpanData s : arrivals) b.addSpan(s, cap);
        List<SpanData> stored = b.spans();
        Set<String> leftover = readTimeWouldRemove(stored);
        System.out.printf("%-46s stored=%s leftover=%s%s%n", label,
                stored.stream().map(s -> s.spanId() + "(p=" + s.parentId() + ")").toList(),
                leftover, leftover.isEmpty() ? "" : "   <<<< GAP");
    }

    public static void main(String[] args) {
        for (String[] o : perms(new String[] {"R", "D", "X"})) {   // triple nest
            order = 0;
            Map<String, SpanData> m = new LinkedHashMap<>();
            m.put("R", span("R", null, "query", "SELECT 1", "app_db"));
            m.put("D", span("D", "R", "query", "SELECT 1", "ds-mid"));
            m.put("X", span("X", "D", "query", "SELECT 1", "ds-inner"));
            run("TRIPLE R<-D<-X, arrival " + String.join(",", o),
                    List.of(m.get(o[0]), m.get(o[1]), m.get(o[2])), 100);
        }
        for (String[] o : perms(new String[] {"R", "D", "G"})) {   // plain grandchild
            order = 0;
            Map<String, SpanData> m = new LinkedHashMap<>();
            m.put("R", span("R", null, "query", "SELECT 1", "app_db"));
            m.put("D", span("D", "R", "query", "SELECT 1", "dataSource"));
            m.put("G", span("G", "D", "result-set", "SELECT 1", "dataSource"));
            run("R<-D + plain G, arrival " + String.join(",", o),
                    List.of(m.get(o[0]), m.get(o[1]), m.get(o[2])), 100);
        }
        order = 0;                                                 // eviction
        run("eviction: R evicted (cap=2) before D arrives", List.of(
                span("R", null, "query", "SELECT 1", "app_db"),
                span("F1", null, "filler", "x", "app_db"),
                span("F2", null, "filler", "y", "app_db"),
                span("D", "R", "query", "SELECT 1", "dataSource")), 2);
    }

    static List<String[]> perms(String[] in) {
        List<String[]> out = new ArrayList<>(); permute(in, 0, out);
        out.sort(Comparator.comparing(a -> String.join(",", a))); return out;
    }

    static void permute(String[] a, int k, List<String[]> out) {
        if (k == a.length) { out.add(a.clone()); return; }
        for (int i = k; i < a.length; i++) {
            String t = a[k]; a[k] = a[i]; a[i] = t;
            permute(a, k + 1, out);
            t = a[k]; a[k] = a[i]; a[i] = t;
        }
    }
}
```

Observed output (only the flagged rows shown):

```
TRIPLE R<-D<-X, arrival R,X,D    stored=[R(p=null), X(p=R)] leftover=[X]   <<<< GAP
TRIPLE R<-D<-X, arrival X,R,D    stored=[R(p=null), X(p=R)] leftover=[X]   <<<< GAP
```

Every other ordering the harness drives reports `leftover=[]`.
