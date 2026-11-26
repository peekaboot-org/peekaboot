# Peekaboot Architecture

> This file and its siblings in `docs/` are for people changing the code. Consumer
> documentation (quick start, configuration, security, the dashboard tour) lives at
> [www.peekaboot.org](https://www.peekaboot.org).

## Module Structure

The reactor table in [`BUILD.md`](../BUILD.md) says what each module contains and which are
published. The sections below follow the same split: backend, frontend, auto-configuration.

## Persisted state

Three stores opt into the filesystem behind `peekaboot.storage.enabled` and share one directory,
`peekaboot.storage.dir`. Both properties, their defaults, the per-application subdirectory and
its fallbacks live on the site under
[`peekaboot.storage`](https://www.peekaboot.org/docs/configuration/#peekabootstorage). The trace
journal also needs `peekaboot.storage.traces.enabled`, which is on unless set to false. The error
digest's file follows the trace journal.

Like `peekaboot.dev-toolbar.enabled`, the storage switch follows the launch context rather than
`peekaboot.enabled`'s resolved value: on for an IDE or `spring-boot:run` launch, off everywhere
else, and an explicit setting wins in either direction. Switching Peekaboot on deliberately in
a shared environment therefore writes nothing to that host's `$HOME`.

`security.properties`, the PBKDF2 hash of the dashboard guard's password, follows the same
switch: with storage off, `CredentialsFile` has no path to write and the password regenerates on
every restart, which the startup report says explicitly, naming `peekaboot.storage.enabled=true`
as what keeps it stable. `peekaboot.security.credentials-file` is the one way around the switch:
an explicit path is written and read regardless of `peekaboot.storage.enabled`, the same way an
explicit property beats a detected default everywhere else in this codebase.

`CredentialsFile` does not read a file its group or others can access, and `PasswordHash` parses
only the iteration count it writes. Either case is a regeneration with a WARN, and the rewrite is
owner-only.

The subdirectory is named from the application's build coordinates rather than its
`spring.application.name`, so two applications sharing a name, or having none, still keep their
history apart. `ApplicationIdentity`, built once by `ApplicationIdentityConfiguration`, holds that
rule and the guard username's artifact-first one, each falling back to the application name.

`StorageDirectory` resolves this path and decides whether this instance writes it. The first
`file(...)` call while storage is on takes the directory's lock through `StorageLock`, which
creates the directory and an empty `.lock` in it. While `peekaboot.storage.enabled` is `false`, or
while another process holds that lock, `StorageDirectory.file(...)` returns empty and no store
that asks it writes.

Every file a store replaces whole goes through `OwnerOnlyFiles.replaceAtomically`, the single
implementation of the mechanism: a temporary of the call's own beside the target, moved over it
in one step, so a reader sees the previous file or the whole new one and two processes never move
each other's unfinished write into place.

The trace journal appends, so it opens its file through `OwnerOnlyFiles.newOutputStream`, which
removes whatever sat at the path and creates the file with `CREATE_NEW`, so a symlink planted
there is deleted, never followed. On a POSIX file system the directory is created `rwx------` and
every file `rw-------` regardless of the process umask, though an existing directory keeps its
permissions. Failure handling stays with the stores: on `IOException` each logs and continues in
memory rather than taking the host application down over an unwritable `$HOME`.

The insights journal appends through `OwnerOnlyFiles.openForAppend`, which never creates the file
and never follows a symlink at its path. `replaceAtomically` creates the file, so an append that
finds nothing there fails and the next write folds (see below).

One process writes a storage directory. `StorageLock` holds an OS file lock on `.lock` until the
JVM exits, so every Spring context of that JVM writes, also one started after a DevTools restart.
A second process on the same `peekaboot.storage.dir` gets no file from
`StorageDirectory.file(...)`: it logs one warning and keeps its insights history, lifecycle log,
traces, error digest and generated password in memory, and it does not take over when the first
process stops.

A directory that cannot be locked is written as before, and the stores report what they cannot
write. Give each instance its own `peekaboot.storage.dir`.

### `insights.snapshot` and `insights.journal`

The insights ring buffers' contents, every level's geometry and one ring per series and level, in
the small versioned binary format `InsightsSnapshotCodec` reads and writes. A coarser level
stores `samples` beside the seven aggregates the API exposes, because the next roll-up weights
its average by it. Version 2 holds percent series from 0 to 100 and time gauges in milliseconds,
as the sampler serves them; a version 1 file holds ratios and seconds and is dropped like any
unknown version.

`insights.journal` holds the entries appended since the last fold, as framed records with a CRC32
that `InsightsJournalCodec` reads and writes. Its header carries the level geometry and the series
table every record follows.

`InsightsSnapshotStore` owns both files. On each boundary of
`peekaboot.insights.persistence.interval` (default: one minute) it appends one record with the
entries newer than the last write, or folds as described below. A fold rewrites the snapshot
through `OwnerOnlyFiles.replaceAtomically` and starts a header-only journal.

A write folds at the run's first write, unless the start took over a snapshot and applied no
journal record. It folds once the journal is as large as the snapshot, after a failed write, and
when the series or a level's end do not continue the journal.

It also folds once more synchronously at shutdown, after the collector has stopped, so the final
write sees quiesced rings. A killed process loses at most one interval, a normal shutdown
nothing. A run that never ticked skips the write rather than overwriting a good file with an
empty one.

A write that fails logs one WARN and the rings stay in memory. `WriteOutage` keeps the following
failures quiet until a write succeeds, so a second outage in the same run is reported again.

With storage off, or on a second instance of one storage directory,
`InsightsSnapshotStore.create` hands the service `SnapshotStore.NONE`, a store that loads, writes
and restores nothing, so `InsightsService` drives one store the same way in every case.

The two files are a cache, never a source of truth, so anything wrong with them costs only the
history. Five things count as wrong:

- A bad magic number.
- A schema version this build doesn't know.
- A ring geometry that no longer matches `peekaboot.insights.levels`.
- An age past `peekaboot.insights.persistence.max-age` (default: the coarsest level's span).
- A date more than five minutes in the future (`InsightsSnapshotStore.CLOCK_SKEW`), which a
  stepped-back clock or a restored backup leaves behind.

The age, clock-skew and geometry checks judge the snapshot with the journal applied, not the
snapshot alone. A result that fails them, or a snapshot that cannot be read, deletes both files
and the rings start empty, exactly as they would with storage off. Each of these logs one INFO
line with the reason. So does a parse that has not arrived within the restore's wait.

The journal is applied on top of the snapshot by entry date (`InsightsJournalReplay`). Series the
configuration no longer has are left out. A cut or damaged record, or one that leaves a hole
after the level's end, ends the replay, and what came before it stays.

An unreadable header or a replay that throws deletes the journal, and the snapshot restores
alone. A journal written for other ring levels than configured is ignored.

Every length read from the file is checked against a plausibility bound before it is used to
allocate. The level-count and ring-size bounds are the ones `InsightsProperties` enforces, so a
configuration that starts can always be read back.

A journal record is read only as far as its bytes go, and the replay builds rings only for the
configured series and levels. What the load allocates follows the files' bytes and the configured
rings, not the lengths a file declares.

Loading never delays startup. `InsightsSnapshotStore.beginLoad` submits the parse to a
virtual thread through `BackgroundLoad` and returns. Each of the collector's level threads then
runs a one-shot restore just before its *first* write, gated by `SnapshotRestoreBarrier` so only
the thread that arrives first applies the snapshot and a level-1 roll-up can never land ahead of
the restore it depends on.

That thread waits up to 5 seconds for the parse. `InsightsSnapshotStore.awaitSnapshot` hands the
parsed snapshot over once and keeps no copy, since the collector copies it into its rings. A
snapshot arriving after the wait is dropped when its parse ends rather than layered on top of live
samples.

Restoring a level's `endEpochMs` is what turns the outage into a visible gap on the chart.
`fillMissed()` then pads exactly the missed interval at that level's next tick or roll-up, capped
at the ring size. It is the same code that pads a suspended laptop or a stalled sampler.

A level thread survives a failing tick or roll-up: `runLevel` catches a `RuntimeException` and
logs it. Anything else ends the thread. Its uncaught-exception handler then logs one ERROR and
`InsightsCollector` starts a new thread for that level, unless the collector is stopping or a
restart has already given the level a new thread.

A roll-up reads the finer level by date, not by position. `SeriesRings.tickWindows` and
`statsWindows` return the entries dated inside the window that ends at the roll-up's boundary:
fewer when the finer level is behind, none of those a late roll-up finds past the boundary. A
window with no entries is stored as `AggregateStats.EMPTY`.

A level above 1 takes each of `median`, `p90`, `p95` and `p99` from the finer windows' value of
the same name by sample-weighted nearest rank, since a window keeps no samples to rank.

A run whose restore did not complete keeps its history, because `InsightsSnapshotStore` refuses
to write while either file holds history it never took over. A process that started with a
handful of samples cannot replace a full retention window. The guard is deliberately blunt: it
is keyed on whether the restore completed, not on how full the live rings have since become, so
a run whose restore timed out never persists again however long it then runs. Lifting it once
every level's ring is as full as the persisted header says would keep the protection and end the
starvation. Until then the file on disk is only ever as good as the last run that restored it.

### `lifecycle.jsonl`

The application's start/stop history: one JSON object per line, at most 1000 events (oldest
dropped first), read on a virtual thread rather than the startup path. `LifecycleEventFile`
rewrites the whole file through the same `OwnerOnlyFiles.replaceAtomically` on every change.
That is cheap at this size (at most 400 KB for the full 1000) and it removes both a
partial-line corruption window and a second trim code path. A line that fails to parse is
skipped on read; the rest of the file still loads.

The file is decoded leniently, so a byte that is not UTF-8 costs its own line and not the read.
Of a file larger than 4 MiB (`LifecycleEventFile.MAX_READ_BYTES`, ten times a full log) only the
newest 4 MiB are read, from the first whole line on, and one INFO line says so. The load sorts
what it read by timestamp, so a file that two instances wrote out of order is served oldest first
and written back that way with the next event.

A start waits for the load without a limit, on its own virtual thread. A stop waits five seconds
and is dropped when the history is still not in memory, because a rewrite from a log that never
read the file would erase it. A write that fails logs one WARN per outage (`WriteOutage`) and the
event stays in memory, so the next write that succeeds carries it.

A start event carries its epoch timestamp, its pid, and a whitelist of
`BuildProperties`/`GitProperties` entries, no others, because a git remote URL can carry the
token it was cloned with and the building user's mail address is personal data. The site names
every kept entry under
[what Peekaboot writes to disk](https://www.peekaboot.org/docs/security/#what-peekaboot-writes-to-disk).
A stop event carries only its own timestamp and pid, since its build belongs to the start it
follows, which the log still remembers. `LifecycleEventRecorder` writes a stop only after its own
ready handed over a start, so a context that closes before it was ready leaves no event.

The git entries come from the application's own `git.properties`, which `ApplicationGitProperties`
reads at the start or at ready, whichever listener runs first, once the main class is known. The
Overview's git card shows the same file where Boot's git info contributor is on. Boot's
`GitProperties` reads the first one on the classpath.
For an application without one, that is whichever library ships one. A `git.properties` in a
classpath directory counts, since only a project being built puts one there and Gradle keeps
resources apart from classes. One inside a jar counts only at the root the main application
class was loaded from, `BOOT-INF/classes/` in a Boot jar.
Where the host sets `spring.info.git.location`, Peekaboot takes Boot's `GitProperties` as it is.
A `git.properties` that cannot be read, or that `Properties.load` refuses for a malformed unicode
escape, costs the git facts alone: one WARN, and the run is recorded without them.

The log's in-memory half runs independently of `peekaboot.storage.enabled`. With storage off, or
on a second instance of one storage directory, `LifecycleEventLog` still records the current
run's start and stop in memory and serves them from there; `LifecycleEventFile` is never
consulted. That is why a dashboard with persistence
switched off still shows one start marker for the run in progress.

### `traces.journal.jsonl` and `traces.snapshot.jsonl`

`TraceJournal`, the `peekabootTraceJournal` bean, runs while `peekaboot.storage.enabled` is on and
`peekaboot.storage.traces.enabled` is not false. It also needs this process to be the one that
writes the storage directory: the bean method asks `StorageDirectory.file(...)` and returns null
where it gets no file. `TraceStoreEventListener` then hands its inputs to
`ErrorDigestRecorder.liveInputs()`, as it does with storage off, and nothing is restored.

`TraceStoreEventListener` hands the journal every input as a `TraceInput`; each passes the
`RestoreGate` to `ErrorDigestRecorder.liveInputs()`, the store and then the error digest, and is
then offered to the writer. Its one thread, the platform daemon thread `peekaboot-trace-journal`,
restores the files, then runs the writer, which flushes every
`peekaboot.storage.traces.flush-interval` (1 s by default) and folds past
`peekaboot.storage.traces.max-journal-size` (64 MB by default). A platform thread, since the
restore and the folds are CPU-bound and would hold a virtual thread's carrier; a daemon, so a fold
still running after the close's wait does not hold the JVM.

At shutdown `ContextClosedEvent` detaches the log capture. OpenTelemetry exports the spans still
in its batch only when the context destroys an `SdkTracerProvider` or `SpanProcessor` bean, so
`OtelTracingAutoConfiguration` makes every such bean, the host's own too, depend on the journal;
Spring destroys them first. OpenTelemetry waits at most 10 s for that last export
(`SdkTracerProvider.close`, `SpanProcessor.close`), and Boot hands every `SpanExporter` bean the
same batch, so a host exporter slower than that can hold Peekaboot's last spans past the journal's
close; they miss the journal. The journal's destroy method then stops the writer and waits up to
10 s for its last fold. That path logs nothing unless its last write or fold fails.

The trace store's inputs (`TraceInput`): every span, log line, completed request and discarded
trace the store takes, and each run's start, one JSON object per line (`JournalLine`,
`JournalCodec`), as the store holds them. Request and response headers, the query string and
parameters arrive capped and masked from capture (`RequestCaptureFilter`). The request path, span
attributes, SQL with its bind parameters, log messages and stack traces are written raw and masked
when read, as on the live store.

`JournalWriter` appends them on one writer thread behind a bounded queue (10,000 inputs). A full
queue drops the input, the run's start aside, so neither the exporter nor a logging thread waits,
and the writer logs one WARN a minute at most with the count. A flush hands the lines to the
operating system without an fsync, so a crash usually loses the last flush interval. Inputs also
wait in memory until the application is ready, and during the restore, and a crash loses those
too. A run line starts each run's lines. It carries the run's `startedAt`, the lifecycle run's
instant, which is known only once the application is ready, so the writer holds a run's inputs
back until then and writes them after the run line. A run that stops before it is ready gets a run
line with `startedAt: null`. A write that fails logs one WARN, the last the writer logs that run,
and ends writing for the run; the store keeps going in memory.

A `VirtualMachineError` in the restore or in a step of the writer, an `OutOfMemoryError` from a
fold's second store say, ends writing the same way with a WARN of its own
(`Peekaboot traces: the journal in <dir> stopped (...)`), and the thread lives on to drain its
queue. When the restore dies of one the gate still opens, and the writer folds nothing, writes
nothing and leaves `errors.jsonl` alone for the run, so both files are read again at the next
start.

Each file starts with a header line carrying the schema version (`JournalCodec.VERSION`). Every
fold starts a fresh journal under a new id, and the snapshot's header names the journal it
absorbed (`JournalFiles`), so a crash between writing the snapshot and starting the next journal
never replays that journal twice. A last line without a line break is what a crash mid-write
leaves; it is skipped without a log line.

At startup `TraceReplay` replays the snapshot and then the journal into the store through its own
methods (`addSpan`, `addLog`, `setRequest`, `discard`), so buckets, eviction, the caps, the
untraced ring and every number the store assigns come out as they did live. They come out in the
order the writer received them, which for two inputs from two threads at once can differ from the
order the store took them. Summaries and issues are computed by the code running now. Each run
line begins a run (`RestorableTraceStore.beginRun`); one naming the run in progress continues it.
A line the store throws on is skipped and counted, and the replay goes on with the next. The live
run begins last, which ends the last restored run: its incomplete traces read `INTERRUPTED`, and
every live span sorts after every restored one. The replay runs before the writer starts, on its
thread. `RestoreGate` holds live inputs back meanwhile and hands them to the live inputs in
arrival order once it is done. Past 10,000 it drops an input, the run's start aside. A held input
that throws is skipped; the writer took it when it was held, so its line is in the journal. A
fold's store throws on it the same way and the fold leaves the line out; a restore that meets it
first skips and counts it. The restore's end logs one WARN with each count. The gate holds writers
only, so for the first moments after startup a reader sees the store partly restored. A trace from
before the restart can answer 404, and a listing or a log search can miss it, until the replay
reaches it.

A fold (`Checkpoint`) runs after the restore, at shutdown, and when the journal passes its size
limit. One that finds no journal line the snapshot lacks (`JournalFiles.holdsUnfoldedLines`), as
after a clean stop, leaves the snapshot as it is and only starts the next journal, so a clean
restart decodes the files once. A snapshot of another version that the restore could not delete
(`JournalFiles.holdsAForeignSnapshot`) is replaced by an empty one even then, since no fold reads
a journal beside it. Otherwise it replays the files into an empty store with the live
store's caps and copies, in arrival order and as written, the lines of what that store keeps: the
spans and logs each trace's caps
kept, its last request, and the newest `max-untraced-logs` lines written outside any trace. Two
stores of the same caps are in memory while a fold runs. A discard, or an eviction from every
bucket, ends a trace's bundle, and the trace's next line begins
another, so the lines before that are not copied. A trace whose caps dropped anything, or whose
bundle began with a line the fold does not copy, gets one remnant line (`TraceRemnant`) where its
bundle began. It carries the dropped counts, whether the trace is complete and failed, the windows
its spans covered, and which kept spans are async, since a span whose async entry span was evicted
would otherwise arrive synchronous. A restore seeds the trace with it before the kept lines arrive,
so the snapshot stays within the caps and the restored trace reads as the store held it, in the run
it began in. A snapshot written under larger caps shrinks at the next fold, not at startup. The
fold reads nothing from the live store, which runs ahead of the files by the writer's queue. A run
line stays where a line of its run stays after it. A line the replayed store throws on is left out
and is in none of the fold's counts (`FoldTally`), so it costs no other line and no later fold or
restore meets it.

The replay of fewer lines evicts in another order, so buckets are not left to the caps. The
snapshot ends with one bucket line (`JournalLine.BucketLine`) for each bucket, Errors and Slow
first, then All, listing its traces in the order the replayed store holds them. A restore holds
All's cap while the snapshot replays, so no trace it names is lost, then lists exactly the traces
each bucket line names, in that order, skipping an id it holds no trace for, and lets All evict
again. Each bucket comes back exactly as the fold saw it. The journal's lines after the snapshot
apply in the order the journal wrote them, so two traces that arrived at the same moment may come
back swapped, and a full bucket may keep the other one of the two. A fold does not keep listing or
log numbers: a replay of fewer lines numbers them anew, which is one reason a cursor from before a
restart is refused.

The files are a cache, so a restore says what was wrong with them once, at INFO: files of another
schema version or with a damaged header (both deleted), the count of lines it could not read,
beside it the count of lines the store refused and the first refusal where there were any, or a
file it could not read at all.

### `errors.jsonl`

The error digest (`ErrorDigest`), written by the trace journal's writer, so it is on disk exactly
when the traces are: `peekaboot.storage.enabled` on, `peekaboot.storage.traces.enabled` not false,
and this process the one that writes the directory. Otherwise the digest lives in memory for the
run.
`ErrorDigestFile` writes a header line with the format's version, then one `DigestedError` a line,
and replaces the file whole through `OwnerOnlyFiles.replaceAtomically`, on a flush that finds the
digest changed (`ErrorDigest.changedSince`, which reads the count and the entries under one lock)
and once more at the writer's stop. In a run whose restore died of a `VirtualMachineError` the
writer leaves the file alone, since the digest was not read back and a write would replace the
stored one. At startup the journal's thread reads it back after the traces'
replay and before the restore gate opens, so the live inputs the gate held count on top of the
restored entries. The replay never reaches `ErrorDigestRecorder`, so a restored failure counts
once, in the run that captured it. `ErrorDigestRecorder` logs one WARN per run when it cannot
record a trace, and the digest misses that entry.

The file is a cache like the trace files: a header of another version deletes it with one INFO, an
undecodable line costs only itself, and a failed write warns once and ends writing for the run. A
restore that skipped or dropped a line has the first flush rewrite the file, so the next start
reads it clean. `lastMessage` is the summary's cause message, masked as the API masks it, so this
file holds nothing the trace files do not, and less.

### API and dashboard

`GET /peekaboot/api/lifecycle/events`, gated by `peekaboot.lifecycle.enabled`, serves the log as
start/stop markers. Each start carries the `version`, `branch`, `shortCommitId` and `buildTime` it
recorded, so one event reads on its own. A start whose predecessor is itself a start, with no stop
between, is flagged `uncleanPrevious`. The dashboard fetches these alongside the ring data from
`/peekaboot/api/insights/*` and draws them as restart markers over the same charts.

`GET /peekaboot/api/lifecycle/runs`, gated by the same property, is the second projection over the
same log. Unlike the events, `LifecycleRuns` carries `version`, `branch`, `shortCommitId` and
`buildTime` forward from the last start that reported them, so a run whose start recorded none still
names what ran. `changed` is the separate, explicit answer to whether this run was a deployment:
which of `version`, `branch` and `commit` differ from the run before. The oldest run has no
predecessor, so it is never a deployment.

A run's `stoppedAt` and `ranForMs` are null, not a guess, when its start has no matching
stop: a `kill -9` writes nothing, so when that run ended is genuinely unknown. `downForMs`, the
gap since the previous run stopped, is null for the same reason whenever the previous run has
no recorded stop. Null there means unknowable, not zero.

The newest start with no event after it is `running` only when the answering process recorded it:
`RunningProcess` holds the JVM's pid and start time, and the start must carry that pid and a
timestamp at or after that start time. The pid alone is always 1 in a container. A newest start
another process wrote reads as an unclean exit.

The 1000-event cap means a long-lived
application's retained history begins mid-cycle, and the oldest surviving event is often a stop
whose own start already aged out. That orphaned stop is normal, and it carries a real
timestamp, so the downtime between it and the next start is still reported.

`/api/features` publishes `lifecycle` from whether the `LifecycleController` bean exists, and
the Lifecycle tab hides on it the way Insights hides on `insights`.

The Lifecycle tab renders `/runs` as a table alongside `/events`' markers on the charts, 20
rows to a page. Paging is client-side over the single fetch: the cap bounds the response at
roughly 500 runs, small enough to hold in the browser rather than adding pagination parameters
to the endpoint.

### Shutdown banner

`ApplicationStoppedListener`, mirroring the ready banner's frame, logs a banner on
`ContextClosedEvent` reporting how long the application ran. Its uptime is measured from
`ApplicationContext.getStartupDate()`, the context's own refresh, rather than the
`ApplicationReadyEvent` timestamp the lifecycle log and chart markers use. It stays available
even when the context closes before the application ever became ready, at the cost of a few
seconds' divergence from "ready" that the banner's own label names.

## Architecture Overview

Everything runs inside the host application's own process. What capture publishes goes to the
store through `CaptureEventPublisher`, never onto the application's event bus.

```
  DevToolbarFilter ──┐                                 ┌── RequestCaptureFilter
  (HTML injection)   └──► Micrometer Tracer ◄──────────┘   (request metadata)
                             tracer.currentSpan()

  OtelSpanExporter          ──► SpanDataEvent          ┐
  PeekabootLogbackAppender  ──► LogCapturedEvent       ├─► CaptureEventPublisher
  RequestCaptureFilter      ──► RequestCompletedEvent  ┘    (Peekaboot's own)
                                                                   │
                                                                   ▼   direct call
                                                       TraceStoreEventListener
                                                                   │
                                                                   ▼
                                       TraceStore (InMemoryTraceStore): three bounded,
                                       insertion-ordered maps, the All/Errors/Slow buckets
                                                                   │
                                                                   ▼
                                       PeekabootController, TraceController, LogController: /peekaboot/api/*
```

## peekaboot-backend

### Package Structure

What each package is for. The classes are left to the packages themselves.

```
org.peekaboot.backend/
├── actuator/         # reads every InsightsSource bean into one raw map
│   └── parsed/       # typed records for the actuator responses
├── analysis/         # how a trace is judged; mapper.trace and service call in, it imports neither
├── config/           # properties, application identity, MVC wiring, the JSON mapper, the URL space
├── controller/       # the controllers of /peekaboot/api and the checks on query parameters
├── datasource/       # what each DataSource connects to, read off the startup path
├── devtoolbar/       # the filter that renders the toolbar into HTML responses
├── domain/           # the records the API serves, one sub-package per dashboard concern
├── errorpage/        # the error page and the two resolvers of peekaboot.error-page.override
├── health/           # the health report of /peekaboot/api/health: its status code, the targets, recent findings
├── insights/         # the metric ring buffers: collector, panels, snapshot, payloads
│   ├── config/       # the insights properties and the panels file
│   └── web/          # /peekaboot/api/insights/*
├── lifecycle/        # the ready and stopped banners, the lifecycle log, build and git info
│   └── web/          # /peekaboot/api/lifecycle/*
├── mapper/
│   ├── actuator/     # actuator records to domain records
│   └── trace/        # a stored trace to the trace the API serves
├── masking/          # the one place "is this key or value sensitive" is decided
├── problem/          # the RFC 9457 errors of /peekaboot/api/**
├── security/         # the dashboard guard, the loopback rule and the credentials they check
├── service/          # what the controllers call: insights, traces, routes, log search, error digest
├── stacktrace/       # how every surface folds a stack trace
├── storage/          # the directory, its one-writer lock, owner-only writes (see Persisted state)
├── text/             # count, byte and uptime formats, the banner frame, lenient enum lookup
├── tracing/
│   ├── async/        # the span around a task handed to an executor
│   ├── bridge/otel/  # finished OpenTelemetry spans into the store
│   ├── capture/      # the request and log sources beside the span bridge
│   ├── config/       # the tracing properties
│   ├── digest/       # the error digest and errors.jsonl
│   ├── event/        # what capture hands the store, and the store keeps
│   ├── interceptor/  # the handler and view observations
│   ├── journal/      # the trace journal: line format, writer, replay, fold
│   ├── listener/     # the span around an @EventListener method
│   ├── query/        # a query's bind parameters onto its span
│   ├── store/        # the trace store, its buckets and what feeds it
│   └── tags/         # the span-tag vocabulary capture, store and mappers share
└── ui/               # what the server-rendered toolbar and error page share
```

### Tracing Flow

1. **Span capture**: `OtelSpanExporter` receives finished spans from the OpenTelemetry SDK and publishes a `SpanDataEvent` through `CaptureEventPublisher`. A span whose conversion or listener throws is left out, with one WARN per exporter, and the rest of its batch is stored. A span failed when its status is ERROR. `DispatcherServlet.processDispatchResult` clears the error request attributes after an error view renders, before `ServerHttpObservationFilter` reads them, so an exception a view renders never reaches the server span that way. Where `peekaboot.error-page.override` answers an exception with a 5xx page, `ServerRequestFailure` fails the server span with that exception itself. Through `TracingHandlerInterceptor.failHandler` it also fails the handler span of the dispatch that threw, so the trace's cause names the handler span, as it does where the exception escapes. A 4xx the page answers leaves the server span OK. So does a page rendered on the ERROR dispatch, which runs after the server span has ended, and so does an application's own view. Their 5xx still fails the trace through its HTTP status
2. **Storage**: `CaptureEventPublisher` calls `TraceStoreEventListener` for each capture event, on the publishing thread, and the listener hands each on as a `TraceInput` to `TraceInputs`: the trace journal while storage is on, which records it after the store took it, else `ErrorDigestRecorder.liveInputs()`, which applies it to the store and then records the trace in the error digest where the input made it complete and failed. The journal hands its live inputs to the same recorder, and replays its files into the store directly, so a restored trace never counts again. The store (`InMemoryTraceStore`) is three insertion-ordered bounded maps: the All, Errors and Slow buckets. The listener also takes its own context's `ApplicationReadyEvent`, the one event it hears through `@EventListener`, whose timestamp names the run in progress (`TraceStore.markRunStarted`), the same instant the lifecycle log stamps the run with. Every bundle keeps the `TraceRun` it was created in.
3. **Log capture**: `PeekabootLogbackAppender` reads `traceId`/`spanId` from the event's frozen MDC map (Logback events carry MDC state, not a live span) and publishes every event but those of the `SecurityPostureListener` logger through the same publisher (see *Log Capture*). One without a `traceId` goes to the store's untraced ring instead of a trace
4. **Request metadata**: `RequestCaptureFilter` uses `Tracer.currentSpan()` to correlate request details
5. **Query**: `TraceInsightsService` reads `TraceStore` directly by `TraceBucket` (ALL/ERRORS/SLOW). `TraceFilter` admits each listing row on the facts `TraceDataBundle.listingRoots()` reads under one lock (root, start, synchronous duration, completeness, failure), and an async row's `outcome` and duration on its own summary, which `RowResources` maps once per request in the summary view whatever the request asked for. A summary page serves those mappings. A `view=full` page maps its own rows again in full, so a filter lays out no row the page leaves out, and a span that lands between the two reads shows in the full row alone. A `path` filter reads the path masked, as the summary serves it (`RowResources.pathOf`, `TraceResources.requestOf`), so nothing matches on what the response hides. The other two buckets' filtered counts take a pass of their own over fresh listing facts, so a trace that changes between passes can be counted under a filter the page did not list it under, or the other way round. `TraceResources` then assembles and enriches each page row (see *Trace Assembly and Enrichment*)
6. **Thresholds**: `IssueDetector` raises SLOW and VERY_SLOW on a span's self time (`SpanNode.selfMs`, its duration minus what its children cover, from `SelfTimes`) and SLOW_QUERY on a query's whole duration, at `PeekabootTracingProperties`' thresholds. REPEATED_QUERY sits on the span that ran the same normalised statement at least `repeated-query-threshold` times (default 5) as its direct children (`RepeatedQueries`). CLIENT_ERROR, a warning, sits on a server root that answered 4xx (`HttpSpanTags.clientErrorStatus`) and leaves the outcome and Errors alone. `PeekabootAutoConfiguration` binds the thresholds whether or not tracing is on. Failing raises no issue of its own, since a failed span's `status` and error fields already say so. The threshold issues apply to a failed span as to any other. `TraceReasons` derives a trace's reasons from the store's admission rules; `outcome` is `ERROR` once complete when the store's sticky failure flag (`TraceData.failed`) is set or one of its reasons is a failure, or for a subtree row once its own reasons hold a failure. The SLOW badge follows the `SLOW` reason. `GET /peekaboot/api/features` publishes the time thresholds (`Features`), so the frontend colours by the same numbers instead of keeping a copy

### Servlet Filters

| Filter | Order | Registered when | Purpose |
|--------|-------|-----------------|---------|
| `RequestCaptureFilter` | `HIGHEST_PRECEDENCE + 100` | a `Tracer` **and** a `TraceStore` bean exist | Captures request/response metadata for traces, and sets the `Server-Timing` header |
| `DevToolbarFilter` | `LOWEST_PRECEDENCE` | a `Tracer` bean exists | Renders the toolbar (markup, inlined styles, data) into HTML responses via `ToolbarShell`, and loads the script that enhances it. Registered for `REQUEST` and `ERROR`: the `REQUEST` dispatch stashes the failing request's method, URI and trace id on a request attribute, and the `ERROR` dispatch reads it back so the error page's bar reports the request that failed, not the `/error` dispatch that renders it. Skips a request the access rule refuses (`DashboardAccess.permitsDebugDetail`), on both dispatches, so a loopback-only local run puts no bar on a page it serves to another machine. A `206` response is passed through as written |
| `DashboardAuthenticationFilter` | `0` | `peekaboot.enabled` **and** `peekaboot.security.enabled` | Challenges every `/peekaboot/**` request with Peekaboot's credentials, whatever else authenticated it |

Both `RequestCaptureFilter` and `DevToolbarFilter` map `/*`. `RequestCaptureFilter`'s order is
documented in the registration: it sits *inside* Boot's `ServerHttpObservationFilter`
(`HIGHEST_PRECEDENCE + 1`), so the server span is current when it runs, and ahead of Spring
Security, so a request the security chain rejects is still captured. `DevToolbarFilter` is
innermost; it wraps the response in a `ContentBufferingResponseWrapper` and injects its markup
into the buffered body. `RequestCaptureFilter` is registered by `TraceCaptureAutoConfiguration`
and follows `peekaboot.tracing.enabled`, since the dashboard reads what it captures;
`DevToolbarFilter` is registered by `DevToolbarAutoConfiguration` and follows
`peekaboot.dev-toolbar.enabled`.

`RequestCaptureFilter` passes a `ParameterReads` wrapper down the chain and reads form parameters
only when the application, or a filter behind it, read them, so it never parses a body itself.
Query parameters come from the query string. `RequestDetails` cuts a name or value over 4,096
characters at its last whitespace before masking it, and assembles the `RequestCompletedEvent`;
the filter decides when it is published. A capture that fails, or an async listener the container
refuses, costs the request its Request tab and a WARN, never the handler's exception.

`DashboardAuthenticationFilter` maps `/peekaboot/*` under the `DispatcherServlet`'s own prefix
rather than `/*`, at a fixed order of `0`. With `spring.mvc.servlet.path=/app` the dashboard's
controllers sit under `/app/peekaboot/**`, and Boot's `DispatcherServletPath` bean is what says
so; without Spring MVC on the class path the guard keeps `/peekaboot/*`. The prefix comes from
`PeekabootPaths.dashboardPath()`, so the guard and everything below agree on where the dashboard is.
It runs on `REQUEST`, `FORWARD`, `INCLUDE` and `ASYNC` dispatches, so an application path that
forwards or includes into the dashboard, and an async dispatch resuming one, meet it too.
Nothing depends on where Spring Security's filter sits: a request carries Peekaboot's
credentials or is challenged, whatever the application's security decided first. With both armed
they lock each other out, so an application that secures `/peekaboot/**` itself sets
`peekaboot.security.enabled=false`.

The filter is the container's mapping, and a host can serve Peekaboot's handlers outside it.
`DashboardAccessInterceptor`, registered with no path pattern at `Ordered.HIGHEST_PRECEDENCE` by
`DashboardAccessConfiguration`, asks the same `DashboardAccess` again for every Peekaboot
controller and UI resource handler, so a path prefix, a case-insensitive matcher or a second
`DispatcherServlet` cannot serve them past the guard.

On a local run no guard filter is registered. `DashboardAccessConfiguration` hands the
interceptor a `LoopbackOnlyAccess` there, so the handler-level check is the only enforcement point
of the loopback rule. Without Spring MVC there is no handler to check, and the interceptor's
registration backs off.

A contributed `SecurityFilterChain` could not do this job. `FilterChainProxy` is
first-match-wins: a chain ordered above the application's would shadow working security, and
one ordered below has nowhere to go, since an unordered application chain already sits at
`LOWEST_PRECEDENCE`. A servlet filter on `/peekaboot/*` sits outside that ordering and needs no
Spring Security on the class path.

An application that wants to supply its own guard overrides the registration by declaring a
`FilterRegistrationBean<DashboardAuthenticationFilter>` bean of its own.
`@ConditionalOnMissingBean` matches on that type, not on the filter class, so Peekaboot's
registration backs off. A bare `DashboardAuthenticationFilter` bean does the opposite of what a
consumer reaching for it wants: it does not stop Peekaboot's own `FilterRegistrationBean`, and
Boot auto-registers the bare bean as a second filter at `/*`, putting HTTP Basic in front of the
whole application. `peekaboot.security.enabled=false` is the other escape hatch, for a consumer
who wants no guard at all rather than a replacement one.

The handler-level check asks the replaced registration's filter (`access()`), never Peekaboot's
generated credentials. A registration that holds no `DashboardAuthenticationFilter` fails startup.

Tomcat 11 suspends a wrapped response after a `RequestDispatcher.forward`
(`suspendWrappedResponseAfterForward`, default `true` since 11.0.0-M18), so the toolbar's write
after the filter chain would be dropped and a `forward:` view served as an empty 200.
`TomcatForwardResponseCustomizer` turns the flag off; Tomcat without the setter is left alone.

`PeekabootPaths` is the one place Peekaboot's URL space is defined: the `/peekaboot` prefix,
the excluded prefixes, and those same exclusions as MVC patterns for the tracing interceptor.
The site lists the prefixes themselves under
[what gets captured](https://www.peekaboot.org/docs/traces/#what-gets-captured). Note what is
not among them: Boot's other default static locations, `/public/`, `/resources/` and
`/META-INF/resources/`. As MVC patterns each prefix gains a `**` suffix, and `/x/**` matches
bare `/x`, so `/error` is excluded while `/errors` stays an application path. That exclusion is
matched on the path, which only ever governs the first dispatch of a request; the `ERROR`
dispatch a failure triggers afterwards is recognised by its `DispatcherType`, never by
re-matching `/error`, so relocating `spring.web.error.path` needs no configuration here.

Everything in `PeekabootPaths` is relative to the `DispatcherServlet`, which serves every
excluded prefix, so the same prefixes double as the interceptor's MVC patterns. The filters match
on the container's mapped path (`getServletPath() + getPathInfo()`, decoded and normalised) rather
than the raw request URI, so neither a `server.servlet.context-path` nor a
`/x/../peekaboot/...` spelling can hide the dashboard's own calls from the exclusion; the
`DispatcherServlet`'s prefix from `spring.mvc.servlet.path` is stripped from that path before
matching. Every URL the toolbar and the error page write into a page (script, stylesheets,
dashboard links, the `basePath` in the bar's data blob) is prefixed with `request.getContextPath()`
and that same `DispatcherServlet` prefix, and `shared/api.js` derives the same base path from its
own module URL, so neither surface needs configuring. The dashboard's entry redirects are
relative for the same reason: Spring's context-relative redirect would drop the prefix.

`PeekabootPathsAutoConfiguration` constructs the single bean with the resolved
`management.endpoints.web.base-path` and the `DispatcherServlet`'s prefix, and threads it into the
guard, both filters, the error page, the interceptor registration and `OtelSpanExporter`'s span
skip. The prefix is read from Boot's `DispatcherServletPath`, which lives in `spring-boot-webmvc`;
a nested configuration conditional on that class reads it, and its `@ConditionalOnMissingClass`
twin uses the root. With Spring MVC on the class path but no `DispatcherServletPath` bean, the
bean logs a WARN and assumes the `DispatcherServlet` is mapped at `/`, so the guard and the
dashboard sit under `/peekaboot/*`. A base path of `/` excludes nothing extra:
there is no prefix to tell those requests apart by. The bean also carries the resolved
`server.servlet.context-path`, because a span's HTTP path tag still has that prefix in front;
`isExcludedRequestPath` strips it before matching, so actuator and Peekaboot spans are skipped
the same with and without one.

A trace also carries a basic method/path/status summary (`summary.request`), read off the root
span's HTTP tags by `HttpSpanTags`, which holds where the request capture never ran. That class
knows all three naming schemes that reach the store: Boot's default server-request observation,
the current OpenTelemetry names and their superseded spelling. The overlay reads
`summary.request` rather than the tags, so the browser never has to know those names. Headers,
query/form parameters and the resolved controller class/method come from the request capture
alone.

Request/response body content and uploaded file names are not captured.
`HttpExchange` has no fields for them; a body-capture pass adds the fields it fills, and is its
own design pass. Capture is the easy half. Masking is the hard one, because `MaskingEngine`'s
key-name rules have no key names to judge in a body: a JSON body is structure, a form post is
pairs, an upload is bytes. Sizing needs a cap and a truncation marker the Request tab shows.

### Server-Timing Header

`RequestCaptureFilter` sets a `Server-Timing` response header
carrying the current trace context in W3C `traceparent` form, before invoking the filter
chain:

```
Server-Timing: trace;desc="00-<traceId>-<spanId>-<traceFlags>"
```

Setting it before the chain runs ensures the header is present even when downstream
handling commits the response early. The toolbar's own idle-mode script, used on pages like
Swagger UI that have no request of their own to report, reads this header off `fetch()`
responses to pick up a trace id for a call it didn't otherwise see. The page gets back the promise
its own `fetch` returned; the bar reads the response on a separate branch and logs what it cannot
read. It skips Swagger UI's own
calls, under the Swagger UI and `springdoc.api-docs.path` prefixes the `IdleMode` blob names
(`ignoredPathPrefixes`); the excluded prefixes need no entry, since they never carry the header.
Nothing stops any other tool that can read response headers from doing the same.

### BFF Pattern

The backend implements a Backend-for-Frontend pattern:

1. **Raw actuator data**: `ActuatorReadings` invokes actuator endpoints in-process (see below)
2. **Typed parsing**: `ActuatorResponseParser.parse(...)` converts raw JSON to typed records. A section the service could not read is null, and so is one that does not bind to its record, each section parsing on its own; inside a section, a collection the response left out binds as empty (the records' compact constructors), so the mappers guard the section and nothing below it
3. **Domain mapping**: `ActuatorInsightsMapper` hands each section to its own mapper and assembles the response
4. **Request policy**: `ActuatorInsightsService.insightsFor` reads the locale and decides on unmasking, then reads, parses and maps

The locale reaches two places only: `CronDescriber`, for the description of a cron trigger, and
`ServerInfo.current`, for the display name of the JVM's default locale.

`ConfigMapper` flattens a nested `@ConfigurationProperties` value to one property per leaf under
a dotted key (`hikari.maximumPoolSize`), indexing list elements the way Spring's own property
syntax does (`servers[0].host`), so the Config tab's filter matches nested keys and values.
Masking runs on the tree first (`TreeMasker`, by leaf key), so a sensitive key anywhere in it
arrives as the single masked leaf its subtree collapsed to.

`ApplicationMapper` copies only the branch, the commit id and the commit time of the application's
own `git.properties` into the `GitInfo` record, for the reason the lifecycle log whitelists its
`GitProperties` entries (see *`lifecycle.jsonl`*): a remote URL can carry a token and a committer's
address is personal data. It reads the file through `ApplicationGitProperties`, since Boot's
`info.git` can show a library's. The host's own `/actuator/info` stays as Boot builds it. The id is
`git.commit.id.abbrev`, else `git.commit.id` cut to seven characters, else `git.commit.id.full`.
A file that names neither branch nor commit reads as no git info.
Where the host turns Boot's git info contributor off (`management.info.git.enabled`, else
`management.info.defaults.enabled`), the Overview shows no git info either. The lifecycle log
records it either way, since the switch governs what `/actuator/info` shows and the history is
Peekaboot's own.
`info.build` stays a free-form map, masked, because the consuming app fills it itself.

The word `insights` marks these BFF endpoints as a path *suffix* and the unrelated metric
rings as a path *prefix*. [`GLOSSARY.md`](GLOSSARY.md#the-word-insights) draws that line.

### JSON on the wire

Peekaboot's REST responses and its insights SSE events are serialised by
`PeekabootJson.MAPPER`, a Jackson mapper with every default but one, never by the application's
own Jackson bean. The dashboard reads camelCase names, tests some fields with `!== null`
(`loggers.js`) and parses every `Instant` as an ISO-8601 string. An application that sets
`spring.jackson.property-naming-strategy`, `default-property-inclusion=non_null` or
`datatype.datetime.write-dates-as-timestamps` must not silently reshape that.

JSON has no NaN and no infinity. Every number that can be either (a meter statistic, an insights
sample, summary or tile) passes `PeekabootJson.finiteOrNull` and reads `null`.

Every point in time a response carries is an `Instant` on its record, so it reaches the browser
as ISO-8601 to the nanosecond, named without a unit (`startTime`, `timestamp`, `startedAt`).
WebKit's `Date.parse` rounds the digits past the millisecond where Chromium and Firefox drop
them, so the browser reads every instant through `format.js`'s `instantMs`, which cuts them
first and agrees with Java's `toEpochMilli` (`SpansTabIT.readsNanosecondStartsToTheMillisecond`). Epoch millis remain only where the
browser does arithmetic on a chart series: a level's `endEpochMs` and the insights stream's
`epochMs`, from which every sample's x position is counted back. A duration is a `long` named
`...Ms`, `MigrationInfo.executionTimeMs` included. One time is a string.
`ServerInfo.currentTime` is the JVM's clock as an ISO-8601 offset date-time in the JVM's zone,
which the Overview renders in the dashboard's chosen zone like every other time.

`PeekabootJsonMessageConverter` (registered first by `PeekabootWebConfig`) claims every return
value whose class lives under `org.peekaboot.backend.`. The scope has to be the value's type,
because Spring MVC picks converters per return value, not per controller.
`InsightsAutoConfiguration` hands the same mapper to `InsightsSsePublisher`.

The mapper writes byte for byte what an unconfigured Boot application writes
(`PeekabootJsonTest.writesExactlyWhatBootsDefaultMapperWrites` in the autoconfigure module), so a
host with default Jackson settings sees the same bytes either way
(`PeekabootJsonMessageConverterTest.writesCamelCaseNullsAndIsoInstants` pins the wire shape). Its
one departure from the defaults is on the factory: the write nesting limit of 500 is lifted,
because a span takes two levels, itself and its `children`, and a chain of 250 spans is within
`max-spans-per-trace` (`TraceResourcesTest.aSpanChainAsDeepAsTheSpanCapMapsAndSerialises`).
Peekaboot writes only its own acyclic records.

Every Peekaboot response goes through it, `PeekabootController.getFeatures()`'s `Features`
record and the `ApiProblem` error bodies included, both under that package prefix.

### API errors

`ApiProblemHandler` answers a 400 for a bad or missing parameter, a 404 for an unknown trace id
and a `ResponseStatusException`, such as the insights stream's 503, with an `ApiProblem`. That is
an RFC 9457 body served as `application/problem+json`, whose `parameter` names the request
parameter at fault, or is null. A 405, a 406 or an unexpected 500 keeps the application's usual
error response.

The advice's scope, `org.peekaboot.backend`, leaves the application's controllers their own
error handling, and it is ordered first so an application-wide `@RestControllerAdvice` cannot
reshape Peekaboot's errors (`ApiProblemHandlerTest`).

The guard's 401 (`CredentialsAccess`) and the loopback rule's 403 (`LoopbackOnlyAccess`) carry the
same body, both written by `Refusals`. An include into the
dashboard gets nothing from the guard. An include cannot set the status, so the including
response keeps its own, and a body would land inside the including page.

Every listing parameter arrives as written, bound into a record of strings
(`TraceListParameters`, `LogSearchParameters`), and the service reads it through
`RequestParameters`. An enumerated value it does not know is refused with the accepted values,
and a number outside its range with that range, where a default or a clamp would answer a typo
with the wrong list.

A `route` that does not start with `/` is refused too (`RequestParameters.routePattern`): every
route a trace or a digest entry names starts with one (`HttpSpanTags.route`), so such a value
could only list nothing. A `locale` that is no well-formed language tag is refused as well
(`RequestParameters.locale`, read in `ActuatorInsightsService.insightsFor`), where
`Locale.forLanguageTag` would have answered in an empty locale without a word. A `limit` above 100
with `view=full` is refused on `limit` (`PageLimit.MAX_FULL`).

A parameter an endpoint does not take is refused before its handler runs, so a misspelled
`buckett` is a 400 naming it with the names the endpoint takes, and so is any parameter sent more
than once, such as `limit=1&limit=2`. `QueryParameterGuard`, an interceptor bound to the API's
handlers (`PeekabootHandlers.isApi`) and registered after the security headers, reads what an
endpoint takes off its handler method: a named `@RequestParam`, the components of a
`@ModelAttribute` record. Spring Security's `continue`,
which its request cache adds to the URL a login sends a browser back to, is let through on every
endpoint. A handler argument of any other kind fails the request, since the guard cannot tell what
it reads, and `ApiProblemIT` sends an unknown parameter to every endpoint the application maps.

`QueryParameterBinding`, an advice scoped to `org.peekaboot.backend` like `ApiProblemHandler`,
turns off Spring's fallback to a same-named request header for every Peekaboot controller, so only
the query string sets a parameter and an endpoint that binds a record declares no binder. The
application's own controllers keep the fallback (`QueryParameterBindingTest`).

### API contract

`GET /peekaboot/api/openapi.json` serves a hand-written OpenAPI 3.1 document,
`META-INF/peekaboot/api/openapi.json` in the backend jar, which `ApiDocument` reads once. It
names every path as Spring maps it, below the DispatcherServlet. Each request gets a copy whose
one server is that request's context path and DispatcherServlet prefix
(`PeekabootPaths.dispatcherServletRoot`), or `/` at the root. `PeekabootApiController` reads the
request from `RequestContextHolder`, since `QueryParameterGuard` fails a handler argument it
cannot read. The copy reaches the client
through `PeekabootJson.MAPPER` inside the `OpenApiDocument` record, like every Peekaboot response.

`OpenApiContractIT` holds the document to the running application. Its paths must equal the
application's mappings under `/peekaboot/api`, each operation's query parameters must equal the
names `QueryParameterGuard` lists in its refusal, and real answers of every endpoint, the event
stream's data included, must validate against the schemas. It reads the full-view trace list to
its last page, a hundred rows at a time, so every stored row's `data` is held to the document.
`ApiContract` validates with networknt's JSON Schema validator in the OpenAPI 3.1 dialect, so a
field added to a record without its schema fails the build.

Every operation documents the two refusals an endpoint can answer before its handler runs, the
guard's `401` and a local run's `403`. `AutoSecuredDashboardIT` and `LocalRunRefusalIT` hold a
real one of each to the document.

`GET /peekaboot/api` is the index drawn from the same document. It lists each path behind the
request's prefix with its operation's `summary` as its purpose, and the document's own path. An
endpoint reaches the index when it reaches the document, and `OpenApiContractIT` fails until it
does.

The schemas are named after the Java types they describe. Every object schema lists every
property as required and allows no other, which is the wire convention that a missing value is
`null` and never an absent key. The `404` of a feature that is off, a `405`, a `406` and an
unexpected `500` are the application's own error responses, so the document describes them in
words only.

`/peekaboot/api/health` answers `503` with a report body, so it documents that response with the
`HealthReport` schema, and `HealthEndpointIT` holds a real `503` to it. `insights/stream`
documents a `503` as well, for another reason (`ServiceUnavailable`).

### Health Endpoint

`GET /peekaboot/api/health` serves a `HealthReport`: the application's health in the nesting
`/actuator/health` uses, a `connection` per DataSource, Redis and mail component, the current
insights problems, the Errors and Slow trace counts and the recent distinct failures.
`HealthReportService` builds it, `HealthController` serves it, and
`PeekabootHealthAutoConfiguration` wires both. That auto-configuration carries a class condition on
`HealthEndpoint` and Spring MVC. It imports `HealthStatusCodesConfiguration`, which bridges Boot's
status mapping, and `HealthConnectionSourcesConfiguration`, which imports
`DataSourceHealthConnectionsConfiguration` and nests one configuration for Redis and one for mail.
HikariCP, Redis and mail classes are named by string in those class conditions, so the endpoint
works where any of them is absent.

The status code is the application's own for the health status. `HealthStatusCodes` bridges the
`HttpCodeStatusMapper` of the primary health group, so
`management.endpoint.health.status.http-mapping` and an application's mapper bean reach it.
`peekaboot.health.fail-on-problems` turns a 2xx into `503` while a problem of severity `error` is
current. It never changes a code that already says failure, and `status` stays the actuator's.

`HealthComponents` masks each component's details through `TreeMasker` and joins the connections it
reads from every source on each report. A top-level indicator takes the connection of its source
only when the source describes exactly one target. A top-level composite takes none itself and
gives each direct child the connection of the bean of that name. Deeper levels get none.

Each `HealthConnectionSource` reads configuration and never opens a connection.
`DataSourceConnections` takes Hikari's configured URL, unwrapped through `java.sql.Wrapper`, and
describes only what `CredentialFreeJdbcUrls.parse` accepts. `DataSourceMetadata`, behind the
dashboard's Overview DataSource card and the startup banner, calls the same method on the URL the
driver reports, so the health report, the Overview card and the banner name a target by the same
rules. Where the method refuses the URL, the card and the banner show no host, no database and no
properties. A reported URL the parser rejects, such as a user before a PostgreSQL host, leaves that
DataSource off both. Otherwise they list the URL's properties, but never those of its authority
(`PropertySource.AUTHORITY`), which is where a user or password sits, nor those of an Oracle
descriptor (`PropertySource.DESCRIPTOR`), whose value masking cannot look inside. A port the URL
leaves out is the driver's default, as the parser reports it.

`parse` hands the parser the URL as written. The jdbc-url-parser rejects what the driver would
reject, such as a user right after the `//` for every product but MySQL, an Oracle descriptor with
text after it, or a SQL Server URL with a zero port. A user and password the driver accepts
before the host, Connector/J's `user:pw@` or ojdbc's `user/pw@`, come back as authority
properties, which no path shows. `parse` adds the rules the parser cannot know, each one because a
credential could end up in a name it reports:

- A URL with a character outside ASCII gets none: mssql-jdbc matches keys with `equalsIgnoreCase`,
  which folds a long s (U+017F) to `s`, so a key the credential check cannot read may still be one.
- After the `//` that follows the scheme (Oracle: `@//`), the only `@` allowed is a single one
  before the first `/`, `,`, `?`, `;`, `#` or `(`, in a first host without a `\`. Any other gives
  none, since Connector/J reads the text after a later host's `@`, or after a `host\instance`'s, as
  a host, so `jdbc:mysql://db1,ZUSR@S3Cx/shop` would name `S3Cx`. A `//` inside an H2 or SQLite path
  opens no user and password.
- A property key that names a user or secret (`MaskingEngine.isSensitiveKey`, or a key holding
  `user`, `uid`, `pwd`, `pass`, `secret` or `token`, matched after `?`, `;`, `&`, `(`, `,` or
  whitespace) gives none.
- A `{` or a `%` gives none. Connector/J decodes `pass%77ord` to `password`, and a quoted value
  hides where a property ends.
- A SQL Server URL with an `&` gives none. mssql-jdbc splits on `;` alone, and an `&` can only sit
  inside a value there.
- A database or instance, of any host, outside `[A-Za-z0-9._$~/-]*` gives none. This catches an
  Oracle URL with parameters after the service name.
- A database, instance or hostname the URL does not spell out gives none. Drivers rewrite names
  (ojdbc drops whitespace from a host, so `ZUSR S3Cx` reads as `ZUSRS3Cx`). The text up to the
  `@` that ends a user and password does not count as spelling a name out, so the login in
  `ZUSRS3Cx/p@ZUSR S3Cx` cannot vouch for that host. The parser's default host `localhost` is exempt.

A DataSource that throws while being unwrapped, such as a routing DataSource without a default
target, gets none either.
`RedisHealthConnections` reads each `RedisConnectionFactory` bean, keyed by bean name, through a
`RedisTargetReader` per client library (`LettuceRedisTargetReader`, `JedisRedisTargetReader`), each
gated on its library. A Lettuce static master/replica factory is reported as its default
localhost:6379. `MailHealthConnections` reads each `JavaMailSenderImpl`.

`RecentFindings` takes level 0's span as its window. It counts the traces that started inside it
and lists the failures last seen inside it, so nothing that only grows reaches the report. A
failure's `lastMessage` is masked again with today's rules, since the digest may hold one from
before it masked.

The dashboard's requests stay untraced and untimed (see *Tracing Integration*), so the opt-in
cannot hold itself at `503` through its own request.

### In-Process Actuator Invocation

Peekaboot never calls `/actuator/*` over HTTP. `ActuatorReadings` holds a list of
`InsightsSource` beans, one per source id (the constants on `ActuatorParsedData`), each a record
pairing that id with a `Supplier`. Seven of them read an endpoint object
`ActuatorSourcesAutoConfiguration` constructs; `health` reads the application's own
`HealthEndpoint` bean, and `spring` reads `SpringBootVersion`/`SpringVersion` and is no endpoint
at all. The health endpoint (see *Health Endpoint*) reads the same `health` source. There is no
discovery step. A source that reads `null` contributes no entry, which is
how an endpoint whose backing bean is absent (a Flyway bean, a `SpringLiquibase` bean, the
`HealthEndpoint` bean, a `LoggingSystem` bean) reports that it has nothing rather than failing.

The `info` endpoint object is built over the application's `InfoContributor` beans plus four
contributors Peekaboot constructs for itself (`InfoReading`): env, java, os and process. None is a
bean, so the application's `/actuator/info` never carries them.

`ActuatorSourcesAutoConfiguration` builds `env` and `configprops` as
`new EnvironmentEndpoint(environment, sanitizingFunctions, Show.ALWAYS)` and
`new ConfigurationPropertiesReportEndpoint(sanitizingFunctions, Show.ALWAYS)`, so
the application's `show-values` settings and endpoint `roles` restriction never
reach them. The application's `SanitizingFunction` beans are passed into both
constructors, and Boot's `Sanitizer` still runs them regardless of `Show.ALWAYS`.
A host `SanitizingFunction` bean still masks a value before Peekaboot sees it.
Short of that, what decides whether a value reaches the dashboard unmasked is
`MaskingEngine` alone, gated by `peekaboot.unmasking.enabled` and the request's
`unmask` parameter (`ActuatorInsightsService.insightsFor`). Spring Boot 4.1 registers no
`SanitizingFunction` of its own, and with `Show.ALWAYS` its `Sanitizer` returns every
value no function changed, so without a host bean Boot masks nothing on this path.

`MaskingRules` is the data behind `MaskingEngine`, and its Javadoc carries the reasons.
The ones a change is most likely to undo:

- Every key is matched in two forms, by the key rules and by the URL-parameter rule alike, and a
  rule that matches either masks. Both are NFKD-decomposed with marks and format characters
  removed, so `paſſword` (long s), an accented `pássword` and a `password` with a zero-width
  space or a combining grapheme joiner inside mask. One form reads a control character as a
  separator, as `app.my<TAB>password` always was; the other removes it, so a NUL inside
  `password` cannot hide it either. Matching stays whole-token: `paſſwordleſſ` stays readable.
  The `PWD` exemption below is checked against the key as written, so `PWD` plus a NUL masks.
  A shown key keeps its original text.
- JDBC connection params, on the dashboard and in the startup banner, take one rule more
  (`MaskingEngine.isSensitiveConnectionParam`): a key that holds `pass`, `pwd`, `secret`, `token`, `key` or
  `credential` anywhere masks, since drivers run words together (`sslpassword`,
  `keyVaultProviderClientKey`). Harmless params such as `keyStoreLocation` mask too. Spring
  config keeps whole-token matching, so `passwordless` and `server.ssl.key-store` stay readable.
- A URL parameter inside a Spring property value, on the Environment and Config tabs, is judged
  as a connection param too (`MaskingEngine.maskPropertyValue`), so the `sslpassword` in a
  `spring.datasource.url` masks there as it does on the connection. Everywhere else, a request
  path, a log line or SQL, a URL parameter keeps whole-token matching so `?keyword=` stays
  readable, and so does a `-D` option inside a property value.
- The exact, case-sensitive key `PWD` is exempt. It is the shell's working directory, set
  in every developer's `systemEnvironment`. Lower-case `pwd` still masks, since that is how
  a SQL Server JDBC URL or a login form names a password.
- Bare `key` and bare `certificate` are not rules. They would mask
  `server.ssl.key-store`, `server.ssl.certificate` and `spring.jpa.key-generator`, which
  are paths or settings. Key material is caught by the PEM value pattern.
- A key whose last token is `uri` or `url` skips both word lists, so an OAuth2 provider's
  `token-uri` stays readable. `SPRING_SANITIZER_KEY_PATTERNS` still apply, and the value
  patterns still catch a credential inside the URL.
- `sig` is a rule for Azure SAS's abbreviated `?sig=` signature parameter.
- `cookie` and `set-cookie` match only as the whole key, so
  `server.servlet.session.cookie.*` stays readable.
- `encrypt.key` and `jwt` mask only as the entire key. As tokens they would mask
  `encrypt.key-store.*` and `spring.security.oauth2.resourceserver.jwt.*`.
- Every value pattern reads a bounded number of characters per input character;
  `ValuePatternCostTest` holds each rule to that.
- `MaskingEngine.maskValue` merges spans that overlap, so two rules that claim part of the same
  text mask it once, through to the later end.
- Some inputs are masked only in part. A PEM block without a footer masks to the end of the
  value, and so does a quoted `name="value"` option whose closing quote is missing. A quoted
  value that holds a line break before its closing quote is not masked. In a URL, up to five
  digits after the colon followed by `/` read as a port, so `user:12345/x@host` has no userinfo
  to mask. A password that holds `@` together with `/`, `?` or `#` is masked only up to an `@`
  inside it.
- There is no entropy-based detection. A git SHA, a UUID or a base64 asset would trip it.

The endpoint objects `ActuatorSourcesAutoConfiguration` builds are not beans. A
second bean carrying `@Endpoint(id = "env")` would collide with the application's
own `EndpointDiscoverer`, which fails at startup on a duplicate id. Each endpoint
is built inside its supplier, once per read, which also keeps this
auto-configuration free of any ordering dependency on Boot's endpoint
auto-configurations.

Health is the one endpoint whose bean is borrowed from the application rather
than built. `HealthEndpoint.health()` already ignores
`management.endpoint.health.show-details`, a setting that belongs to the
application's own public `/actuator/health`, so building a separate instance
would only duplicate the contributor registry and the group configuration for
nothing. The dashboard therefore has full health while `/actuator/health` keeps
answering anonymous callers with the aggregate status only.
`ActuatorResponseParser` accordingly parses the bare `HealthDescriptor` shape
(`status`, `components`, `groups`), not a `WebEndpointResponse` wrapper. A
composite contributor (Spring's `db` as soon as there are two DataSources, or any
custom composite) nests its children under a further `components` map.
`HealthMapper` flattens them to `db/<name>` for the dashboard's single list, and
`DataSourceMapper` reads each DataSource's own child status rather than the
composite's aggregate.

Borrowing the bean still needs it to exist. Spring Boot only *creates* the
`HealthEndpoint` bean when `@ConditionalOnAvailableEndpoint` matches, and with
Spring defaults `health` is exposed, so the bean exists without any help. The gap
is an application that narrows `management.endpoints.web.exposure.include` to
exclude health: without the contributor, the bean would not exist and the
dashboard would show no health data either.
`PeekabootEndpointExposureOutcomeContributor` reports the health endpoint as
exposed while `peekaboot.enabled=true`, closing that gap. The regular HTTP mapping
under `/actuator` still applies `management.endpoints.web.exposure` on its own,
unaffected by the contributor, so `health`'s reachability over HTTP is governed by
that property alone. Every other endpoint Peekaboot reads it constructs itself, so
nothing else needs forcing here. The contributor does not decide access either.
`OnAvailableEndpointCondition` resolves `management.endpoint.health.access` before
exposure contributors run. `access=none` still removes the bean.

See [www.peekaboot.org/docs/security](https://www.peekaboot.org/docs/security/) for what this
exposure model means in practice for securing a deployment.

### Error Page

`ErrorPageAutoConfiguration` registers Peekaboot's page in the fallback slot Spring Boot leaves
for one. With `peekaboot.error-page.override` set, it registers it in either of two places that
outrank an application's own. With the override on, an exception the application's own
`@ExceptionHandler` maps still renders Peekaboot's page for an HTML request, as a 500 with an
ERROR log line where nothing in Spring maps it.

Unset, it registers a `View` bean named `error`, the fallback slot. Boot's own whitelabel view
is `@ConditionalOnMissingBean(name = "error")`, so registering this bean first, `before
ErrorMvcAutoConfiguration`, backs the whitelabel view off and leaves Boot's
`BeanNameViewResolver` to resolve `error` to `PeekabootErrorView` instead. It carries every
condition Boot puts on its own whitelabel page (`spring.web.error.whitelabel.enabled` and no
`error` view template available) plus its own `@ConditionalOnMissingBean(name = "error")`. An
application therefore keeps whatever error page it already has: an `error` view bean of its
own, an `error` template, or a static `error/*.html`.

Set, it registers two beans instead. An `ErrorViewResolver` at `Ordered.HIGHEST_PRECEDENCE`,
which `BasicErrorController` consults before it falls back to the view name `error`. So
Peekaboot's page outranks a template, a static `error/*.html` and an application `error` bean,
without clashing with any of them. None of the back-off conditions apply on that path, including
the whitelabel one, since it does not depend on `BeanNameViewResolver`. The resolver answers
every error. Registering any `ErrorViewResolver` removes Boot's own, so returning null would
drop the `error/4xx.html` convention rather than fall through to it.

Alongside it sits a `PeekabootErrorExceptionResolver`, a `HandlerExceptionResolver` at
`Ordered.HIGHEST_PRECEDENCE + 1`, one slot after Boot's `DefaultErrorAttributes`, which is
itself a resolver at that exact value. A tie there would be broken by bean registration order,
which nothing here controls, so the next slot is the deterministic choice. Despite what the
value suggests, the slot does not give `DefaultErrorAttributes` a chance to record the
exception first. `DispatcherServlet.processHandlerException` calls
`WebUtils.exposeErrorRequestAttributes` once a resolver has answered, which sets
`jakarta.servlet.error.exception` if nothing already has, and `DefaultErrorAttributes.getError`
falls back to exactly that.

The resolver is what lets Peekaboot's page win where an application renders its own from a
`@ControllerAdvice`: that advice resolves inside the REQUEST dispatch, so no ERROR dispatch ever
follows it, `BasicErrorController` never runs and no `ErrorViewResolver` is ever consulted. It
outranks the `HandlerExceptionResolverComposite` that holds `ExceptionHandlerExceptionResolver`
among Boot's own resolvers, so it intercepts the exception first.

It returns null, falling through to the application's own handling, unless the `Accept` header
names `text/html` with a quality above zero. A bare `*/*` or a missing header does not count, so
Swagger UI, curl and `fetch()` keep getting the application's own error response, as does an XHR
asking for JSON. It also returns null for a response already committed, as Spring's own
resolvers do. It returns null as well for an exception Peekaboot's own API answers, which
`ApiProblemHandler.answers` decides from the handler's package and the advice's exception
methods. A browser asking an API URL therefore keeps the problem body, which goes out for any
`Accept` that admits JSON. That check follows causes the way Spring's advice lookup does, so it
is asked only after the cycle check below.

The status is Spring's own, asked in Spring's order: a private
`ResponseStatusExceptionResolver`, then a private `DefaultHandlerExceptionResolver`, both
answering against a response wrapper that records `sendError` instead of sending it. A
`ResponseStatusException` keeps its status and headers, an `@ResponseStatus` its reason, a
`TypeMismatchException` or an unreadable body its 400, and the headers either mapping sets
(`Retry-After`, `Allow` on a 405) reach the real response; the recorded message becomes
`ERROR_MESSAGE`. `spring.mvc.log-resolved-exception` gives that status mapping the same warn log
category it gives Spring's own resolvers. A cause chain is first walked once with cycle
detection: a cyclic one never reaches Spring, whose cause walks would never return from it, and
only the status of a `ResponseStatusException` or a merged `@ResponseStatus` on it counts, without
headers or reason.
Where that mapping answers without a status, a client that already hung up, the resolver returns
its empty `ModelAndView`, so nothing is written to the dead connection.

Anything left is a 500, and since the container would have logged it at ERROR had it escaped,
the resolver logs it on its own logger as `Request processing failed: <exception>`, the message
`FrameworkServlet` gives it. For a 5xx it first fails the request's server span and handler span
with the exception through `ServerRequestFailure`, because Spring clears the error request
attributes before its observation filter reads them and passes the handler interceptor no
exception a resolver answered (see *Tracing Flow*). It sets `RequestDispatcher.ERROR_STATUS_CODE`,
`ERROR_REQUEST_URI` and `ERROR_METHOD` on the request (nothing else sets that last one on a
REQUEST dispatch), so `PeekabootErrorView` renders the same request line, status and reason it
would off a real ERROR dispatch.

All three paths sit behind `@ConditionalOnWebApplication(SERVLET)`,
`@ConditionalOnClass({DispatcherServlet.class, ErrorAttributes.class})`, `peekaboot.enabled` and
`peekaboot.error-page.enabled`; the override cannot turn the page on by itself. None of them
cover an application that excludes `ErrorMvcAutoConfiguration` outright, which leaves no
`ErrorAttributes` bean for any page to render with, or one whose own error page is an
`ErrorController` on `/error`, which backs `BasicErrorController` off entirely and with it
anything that would consult an `ErrorViewResolver`. Boot's own whitelabel view cannot express
the first case either, since Peekaboot's configuration runs ahead of it.

The page shows the status and its reason, the request line, the exception class and message,
and the stack trace with the application's own frames marked (from
`AutoConfigurationPackages`). The message and the trace go through `MaskingEngine.maskValue`
before folding and escaping, as a failed span's do; the request path is shown as sent.
`PeekabootErrorView` asks the application's own `ErrorAttributes`
bean for these, with every `Include` switched on, so the application's own
`spring.web.error.include-*` settings are neither read nor changed: they govern what the error
*response* a client receives carries, and this is a page rendered for a developer looking at the
browser.

The bean reaches the view wrapped in `GatedErrorAttributes`. `ErrorPageAutoConfiguration.errorView`
is the one place all three paths get their view, so each asks `DashboardAccess.permitsDebugDetail`
first: a request the rule refuses is answered with `ErrorAttributeOptions.defaults()`, and the page
shows it the status, the reason and the path and no exception, message or trace.

`peekaboot.error-page.enabled` gates all three paths, defaulted from the same launch-context
detection as `peekaboot.enabled` and the dev toolbar: on for a detected local run, off otherwise,
with an explicit setting winning either way (see *Conditional Loading*). Switched on for a
deployment launch, the page serves full stack traces outside the guard, so `SecurityPosture`
adds a WARN to the startup report. For a request the access rule permits, it carries the dev
toolbar the same way any other HTML response does, because `DevToolbarFilter` is registered for
the `ERROR` dispatch as well as `REQUEST` (see *Servlet Filters*). The bar reports the request
that failed, never the dispatch that rendered the page.

### Stack Traces

One classifier, `StackTraceFolding.fold`, decides which frames fold away on both surfaces a
trace reaches: `StackTraceHtml` renders it into the error page's `<pre>`, and
`WireStackTraces` renders it into the wire `TraceLog` the Logs tab consumes and into a failed
span's `exception`.
Both callers pass it the same `exclusions` and `applicationPackages`; neither surface
classifies anything of its own.

A frame hides when its whole line contains one of the exclusion patterns as a substring, the
same test Logback's `%wEx` conversion word applies. A pattern is neither a prefix nor a regex.
That is why a marker like `ByCGLIB` works as a pattern: it need not anchor to a package
boundary, only appear somewhere in the line a CGLIB-generated proxy class leaves behind.

`ExclusionPatterns.resolve` picks the list once, from the first source that carries an opinion.
An explicit `peekaboot.stack-trace.exclude` wins when it is a non-empty list, and replaces the
built-in list rather than adding to it; an empty list there is not an opinion, and falls
through the same as leaving the property unset. `logging.exception-conversion-word` comes next.
A `%wEx{...}` block that names nothing past its leading depth, such as `%wEx{full}` alone, is
itself an opinion, "exclude nothing," so resolution stops there with an empty list and folding
hides nothing; `ExclusionPatternsTest` pins `resolve(List.of(), "%wEx{full}")` at empty for
exactly this reason. A conversion word carrying no `%wEx{...}` block at all carries no opinion,
and falls through instead to `ExclusionPatterns.DEFAULT`, a built-in list of reflection,
container, web-framework, template-engine and driver/proxy packages.

`StackTraceFolding.fold` decides `isApplicationFrame` before it ever tests a frame against the
exclusion list, against the `applicationPackages` list its caller passes in. An application
frame is therefore never hidden, even where its line would otherwise match a pattern. The list
is a parameter because `StackTraceFolding` lives in `peekaboot-backend` and cannot reference
`ApplicationPackages`, which resolves it and lives in the autoconfigure module. What is
highlighted and what is hidden come from separate inputs and never merge. Non-frame lines never
enter a hidden run either. These are the exception header, `Caused by:`, `Suppressed:`, and an
elision (`... N more` from `printStackTrace`, `... N common frames omitted` from the log path).
Only a tab-led line that starts `at ` once its own leading tabs are gone is a candidate.

Folding is a render-time decision. `PeekabootLogbackAppender` captures a throwable's full trace
unfolded, capped at 1000 lines with a `... N lines omitted` marker (`... 1 line omitted` for
one) so a `StackOverflowError`'s
1024-frame trace can't dominate retention; `TraceLogMapper` runs
`StackTraceFolding.fold` over that stored trace on every read, and `PeekabootErrorView` runs it
fresh on every request. The full trace is therefore always present on the wire.

Both surfaces fold with one `StackTraceRules` bean. `StackTraceRulesConfiguration` resolves it
once from `peekaboot.stack-trace.fold`, `peekaboot.stack-trace.exclude`,
`logging.exception-conversion-word` and `AutoConfigurationPackages`, and both
`PeekabootAutoConfiguration` and `ErrorPageAutoConfiguration` import it, so either runs without
the other. `ErrorPageAutoConfiguration` imports `MaskingConfiguration` as well, for the engine the
page masks with. `peekaboot.stack-trace.fold=false` empties the exclusion list rather than travelling
as a flag, so `TraceLogMapper` and `PeekabootErrorView` ask the same question with nothing
to match: `hiddenFrames` comes back empty, and the page renders no reveal control because
nothing was hidden. The browser classifies nothing on either surface; `shared/log-entry.js`
renders the ranges it is given, the same way `StackTraceHtml` does server-side.

The error page's reveal script ships twice, inline in a `<script>` tag and linked via
`<script src>`, for the same reason its CSS does (`InlinedStylesheets`). A strict `script-src`
drops the inline copy, and an authorization gate in front of `/peekaboot/**` drops the linked
one. Either copy only opens every folded `<details>` at once; the per-run disclosures are native
HTML and work with no script running at all.

## peekaboot-frontend

Static resources served from `/peekaboot/ui/`, backing four UI surfaces that share one design
system: the standalone dashboard, the dev toolbar injected into host-app pages, the
trace-detail overlay, and the error page rendered in place of Boot's whitelabel page. This
section covers only the headline decisions;
`peekaboot-frontend/README.md` has the shared-layer split, the shadow-DOM delivery mechanism,
theme resolution, accessibility invariants, the ids the test suite depends on, and the file
inventory of every module under `META-INF/peekaboot/ui/`.

### Design Principles

- **No build step**: plain HTML/CSS/JS, ES modules
- **Shared design system, four surfaces**: the dashboard and error page documents consume
  `assets/tokens.css`/`base.css`/`components.css` directly, the toolbar's and overlay's shadow
  roots via `attachSharedStyles()`. A doubled selector (`:root, :host { ... }`) lets the
  identical stylesheet apply in both contexts, so no surface carries its own palette,
  element builder, duration thresholds or collapsible-group CSS
- **Shadow DOM**: toolbar and trace-detail overlay isolated from host app styles
- **Bundled webfont**: Geist and Geist Mono ship inside the frontend jar and are served from
  it, never a CDN. The dashboard picks them up from an `@font-face` rule in `tokens.css`; the
  two shadow-rooted surfaces cannot (a rule declared inside a shadow root is ignored) and
  register the same faces through `shared/fonts.js`
- **Responsive dashboard, desktop-first toolbar and overlay**: the toolbar wraps and the
  overlay's gantt reflows below 768px, but both assume a desktop viewport for their full layout
- **Lazy loading**: trace-detail overlay JS loaded only on first use (dynamic `import()` from
  `toolbar.js`; eager static import from `dashboard/main.js`)
- **Theme support**: `--pk-*` custom properties resolved once in `shared/theme.js` from
  `localStorage['peekaboot-theme']`, falling back to `prefers-color-scheme`, shared across all
  four same-origin surfaces; the error page reads the same key through `assets/theme-boot.js`

`peekaboot-frontend/README.md` covers the fill/text token split and which tokens have to
change together. Changing one without its pair is how an accessibility regression ships.

## peekaboot-spring-boot-autoconfigure

Auto-configuration classes that wire everything together. The `@AutoConfiguration` classes
are registered in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, which
`RegisteredAutoConfigurations` reads for the tests that check every one of them at once. The
hooks that run before or outside the application context are registered in
`META-INF/spring.factories`:

- `PeekabootDefaultsEnvironmentPostProcessor` detects the launch and contributes the default
  properties (see *Conditional Loading*, *Default Properties* and
  *Automatic Dashboard Security*).
- `PeekabootEndpointExposureOutcomeContributor` reports `health` as web-exposed while Peekaboot
  is on (see *In-Process Actuator Invocation*).
- `LogbackCaptureReinstaller` re-attaches the log-capture appender after Boot re-initialises
  Logback (see *Log Capture*).

Every `@Bean` method across these auto-configurations is `@ConditionalOnMissingBean`, apart from
the two `DependsOnTraceJournal` post-processors in `OtelTracingAutoConfiguration`, which add a
dependency on the trace journal and have nothing to replace. All but one are named with the
`peekaboot` prefix, because component scanning names an application's own bean after its class
and a second definition under a taken name stops the context. The exception is the fallback
`error` view, which Boot's `BeanNameViewResolver` finds by that name. A filter registration sets
no name of its own, so its filter is registered under the bean name. `PeekabootBeanNamesTest`
holds the rule. Most conditions match by type, so an application bean of the same type replaces
the default outright; the `FilterRegistrationBean`s match by their deduced generic type, so an
application's other filter registrations never back one of them off. A named set matches by name
instead: `peekabootTracingInterceptorConfigurer`, because several `WebMvcConfigurer` beans
coexist and a type match would let one suppress them all; and each `InsightsSource` bean in
`ActuatorSourcesAutoConfiguration`, because they share that one type and a type match would let
an application overriding a single source suppress every reading. The datasource metadata is a
bean of its own type, `DataSourceMetadataList`, rather than a `List<DataSourceMetadata>`: Spring
resolves the list type by collecting `DataSourceMetadata` beans first, so one application bean
of that type would have replaced the whole list. Both the list and the ready banner collect
DataSources the way Boot's `db` health contributor does, `defaultCandidate = false` ones
included: plain `Map<String, DataSource>` injection skips those, and Boot's how-to declares a
second DataSource that way.

The trace store is declared as its class, `InMemoryTraceStore`, with a condition that names
`TraceStore`: the trace journal takes a `RestorableTraceStore`, and Spring matches that against
the declared type of a bean that does not exist yet. An application's own store is found for
certain only when it is declared as a `RestorableTraceStore` or a class that implements it;
declared as `TraceStore` it is found only if it was created before the journal.

The ten actuator mappers (`HealthMapper`, `ConfigMapper` and the rest of
`mapper/actuator`) are not beans. `ActuatorInsightsMapper` builds them from the `MaskingEngine`
and `ActuatorInsightsService` builds that mapper, so the one override point for that pipeline is
the service itself. They are stateless and need at most the engine, and ten
`@ConditionalOnMissingBean` methods nobody overrode bought nothing but wiring.

The trace mappers follow the same rule: `TraceInsightsService` builds `TraceResources`, which
builds every mapper below it, so that is the one place the masking policy enters the trace
pipeline.

### Conditional Loading

Most of the auto-configuration classes carry `@ConditionalOnPeekabootServlet`, directly or
through `@ConditionalOnPeekabootTracing`. It composes the servlet guard and the master switch:

```java
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@interface ConditionalOnPeekabootServlet {}
```

Each adds its own conditions on top. Class-level ones guard what the starter's closure leaves
optional: `spring-boot-health`, the OpenTelemetry SDK and datasource-micrometer's tracing
observation support, which the starter brings but a host can exclude. Spring MVC is the other
case. The starter does not bring it, and the configurations whose beans name its types carry a
class condition, so a servlet application without it still starts.

HikariCP, Logback, Flyway, Liquibase, Hibernate, Redis (with Lettuce and Jedis each a level below)
and mail are optional too, each guarded a level down on a nested `@Configuration`, except that
`HibernateStatisticsMetricsAutoConfiguration` guards Hibernate at class level, having nothing to
bind without it. `InfoEndpoint` and `ObservationRegistry` arrive with hard dependencies of this
module and the backend, so no consumer of the starter can be without them.

`PeekabootAutoConfiguration` registers the servlet-only `PeekabootWebConfig` next to the
controllers, services and actuator wiring, all as explicit `@Bean` methods.
`ServerUrlResolver`'s dashboard check looks for the bean named `peekabootWebConfig`. It needs
the servlet guard because `PeekabootWebConfig implements WebMvcConfigurer`, a servlet-only type.
It carries `@ConditionalOnClass(WebMvcConfigurer.class)` for the same type: without that condition
Spring cannot introspect the configuration class in an application that has no Spring MVC on its
class path, and the context does not start. `SpringMvcAbsentTest` pins that it starts.

`ServerUrlResolver` builds the service URL the ready banner prints from the web server's port,
`server.servlet.context-path` and `server.address`. An address that is unset, blank, `0.0.0.0` or
`::` prints as `localhost`. The scheme is `https` where Spring Boot considers TLS on, so a key
store or an SSL bundle under `server.ssl` is enough and `server.ssl.enabled=false` keeps `http`.
The Swagger UI URL follows `springdoc.swagger-ui.path`.

`PeekabootWebConfig` is also the only thing that serves the UI bundle. The bundle lives at
`classpath:/META-INF/peekaboot/ui/`, outside every static location Boot serves by default,
and its resource handler exists only while `peekaboot.enabled` is `true`. With Peekaboot off
nothing under `/peekaboot/**` answers, the assets included.

`PeekabootWebConfig` binds its interceptors to Peekaboot's handlers and to no path. The UI's
responses carry `Content-Security-Policy: default-src 'self'; object-src 'none'; base-uri 'self';
frame-ancestors 'none'; form-action 'self'`, `X-Frame-Options: DENY` and
`X-Content-Type-Options: nosniff`, each only where the response does not carry that header yet.
The UI has no inline script or style and loads nothing from another origin; `UiSecurityHeadersIT`
holds it to that in a browser.

The servlet guard prevents dead beans, not a crash. Without it a WebFlux or non-web application with
`peekaboot.enabled=true` would register the controller, services and mappers with nothing
servlet-specific ever invoking them. `ApplicationContextRunner` confirms the context still
starts cleanly: only `WebMvcConfigurationSupport` calls back into `WebMvcConfigurer`, and it is
itself only wired up in a servlet context. `PeekabootTracingAutoConfiguration`,
`OtelTracingAutoConfiguration` and `QueryParameterAutoConfiguration` carry the guard for the
same reason: everything that reads the trace store, including the tag
`QueryParameterAutoConfiguration` writes, is servlet-only. Without the guard they would fill an
`InMemoryTraceStore` for nobody, and the query tag would put raw, unmasked bind values on every
span with no Peekaboot UI to show them.

`PeekabootLifecycleAutoConfiguration` and `PeekabootStorageAutoConfiguration` carry no servlet
guard, because the ready/stopped summaries, the run history and the storage directory must work
in a plain non-web application. Only the lifecycle API's `LifecycleController` bean is
servlet-gated on its own. `PeekabootDefaultsEnvironmentPostProcessor` splits the same way:
activation, storage detection and the off defaults are web-type independent, while
`peekaboot-defaults.yml` and the dev-toolbar defaults are skipped off-servlet.

The tracing configurations carry `@ConditionalOnPeekabootTracing` instead, which adds
`peekaboot.tracing.enabled` (default on). It nests `@ConditionalOnPeekabootServlet` rather than
repeating its property condition: Boot keeps one `@ConditionalOnBooleanProperty` per
meta-annotation path, and `ComposedConditionsTest` pins that both match exactly where the copied
stacks did. Handler, view, async, event listener and query observations and the request and log
capture are tracing, and with it off there is no store for them to land in.
`DevToolbarAutoConfiguration` does not read that property at all, so with
`peekaboot.tracing.enabled=false` the toolbar is still injected into every page with nothing
captured behind it.

There is no `matchIfMissing` fallback for `peekaboot.enabled` or `peekaboot.dev-toolbar.enabled`. Both
default from `PeekabootDefaultsEnvironmentPostProcessor` into a `peekabootDetection` property
source at lowest precedence, so any explicit application setting wins in either direction.
`PeekabootOffByDefaultTest` pins that for every registered auto-configuration at once: without
`peekaboot.enabled`, no Peekaboot bean exists, the toolbar switch alone included. The
toolbar keys on the same local-development detection as `peekaboot.enabled`, not on
`peekaboot.enabled`'s resolved value, so turning Peekaboot on deliberately in a shared
environment does not inject the toolbar into every page as a side effect.

`peekaboot.error-page.enabled` is the fourth switch this detection derives, alongside
`peekaboot.enabled`, `peekaboot.dev-toolbar.enabled` and `peekaboot.storage.enabled` (see *Persisted
state*): on for a detected local run, off otherwise, with an explicit setting always winning.
`peekaboot.stack-trace.fold` is not among them: folding only decides what a trace shows first,
so it defaults to `true` on every launch.

`LocalDevDetector` starts from the heuristics Spring Boot DevTools itself uses: the `main`
thread on the JVM's system class loader, with no test-framework or AOT frame on the stack. Those
hold for every exploded-classpath launch, so two signals of its own decide: `java.class.path`
must contain a build tool's output directory, and `ContainerRuntime.current()` must report
`NONE`. The class's Javadoc has the checks and their order. What a change is most likely to undo:

- The system class loader is compared as an object. A match on its class name would also take a
  servlet container's webapp loader.
- A launch DevTools restarted (`RestartClassLoader`) skips the thread and loader checks, which
  DevTools' own gate has already made, and keeps the other two. DevTools' class-path signal
  accepts any directory, so without them a Jib image or Boot's `extract` layout that ships
  DevTools as a runtime dependency would read as local.
- A jar's `Class-Path` manifest attribute counts as part of the class path, because IntelliJ's
  "JAR manifest" command-line shortening leaves one temp jar on `java.class.path` and moves every
  real entry into its manifest.
- Runtime names are not matched in `/proc/1/mountinfo`, because a host lists the overlay mounts
  of its own containers there. A mount point of `/etc/hostname`, `/etc/hosts` or
  `/etc/resolv.conf` is the signal.

So an IDE run, `mvn spring-boot:run` and `gradle bootRun` default to on. A `java -jar` of the
packaged artifact, a war in a servlet container, a native image, an AOT-processed build, a
test, a Jib image, the `extract` layout, a plain `java -cp` of jars and anything inside a
container default to off. What is left for an explicit `peekaboot.enabled=false` is the one
shape the signals cannot separate: a build output directory mounted into a non-container
process that is not a developer's own launch. A launch read as local answers only the machine it
runs on (see *Automatic Dashboard Security*), so a misread launch on a shared host or a CI runner
does not serve the dashboard to the network. `peekaboot.remote-access.enabled=true` opens it.

`peekaboot.lifecycle.enabled` is read by a `@ConditionalOnBooleanProperty` condition, since it
has to be evaluated before any Peekaboot bean exists, and is also bound as
`PeekabootProperties.Lifecycle.enabled` so it carries configuration metadata and shows up on
the Config tab like every other switch. `PeekabootAutoConfiguration` binds
`PeekabootTracingProperties` and `InsightsProperties` the same way, whatever their own switches
say, so `peekaboot.tracing.enabled=false` and `peekaboot.insights.enabled=false` stay visible there.

That double reading is why a switch a condition gates on must not hold a value the two read
differently. `@ConditionalOnBooleanProperty` compares the raw value with `true`, ignoring case,
while the binder behind `PeekabootProperties` trims it and also reads `yes`, `on` and `1` as true,
so `peekaboot.error-page.override=yes` would leave the page off with the Config tab showing it on.
`no`, `off` and `0` read as off both ways and are accepted, except on
`PeekabootPropertyKeys.SWITCHES_ALSO_GATED_ON_FALSE`: `peekaboot.error-page.override` is also
gated with `havingValue = false`, which matches only `false`, so `no` would drop the page entirely.
An unconvertible value has no binder reading at all and is refused too. A blank one, which compose
or Helm templating leaves for an unset variable, reads as unset: `BooleanSwitches.treatBlanksAsUnset`
puts the value the next property source down holds (placeholders resolved), or else the detected
value, or else the switch's entry in `PeekabootPropertyKeys.UNSET_SWITCH_DEFAULTS`, into a
`peekabootBlankSwitches` source at the highest precedence. It covers the detected keys too, since
`peekaboot.storage.enabled` binds to a primitive `boolean` as well, and the conditions and the
binder both read the value it puts there. That source is a startup snapshot; a later environment
refresh does not update it. `BooleanSwitches`
checks every key in `PeekabootPropertyKeys.BOOLEAN_SWITCHES` in
`PeekabootDefaultsEnvironmentPostProcessor` once the blanks have given way, so a `yes` beneath a
blank is checked too, and before the detection reads `peekaboot.enabled` through the same lenient
conversion, and throws Boot's `FailureAnalyzedException`, so startup ends
with a failure report naming each offending property and value. Both lists are hand-kept because
a native image has no class files to scan for the conditions at startup; `BooleanSwitchesTest`
reads every `@ConditionalOnBooleanProperty` on a registered auto-configuration and fails when a
`peekaboot.*` key is missing from either. `PeekabootDefaultsEnvironmentPostProcessorTest` fails
when a switch is in both the detection map and `UNSET_SWITCH_DEFAULTS` or in neither, and
`BooleanSwitchesTest` when the value a property bean starts with differs from what the switch reads
as unset. A YAML file never trips the check with a bare `yes`:
YAML 1.1 already parses that as the boolean `true`.

### Default Properties

`PeekabootDefaultsEnvironmentPostProcessor` loads four yml resources with lowest precedence,
all overridable by an app's own `application.yml`:

- `peekaboot-defaults.yml`. Enables full observability, but only when Peekaboot is enabled
  *and* the application is a servlet web application, since everything that would read it is
  servlet-only. The web type is `spring.main.web-application-type` where the environment
  carries it, and otherwise the one `SpringApplication.getWebApplicationType()` deduced,
  because Boot binds `spring.main.*` onto the application only after the post-processors have
  run. The dev-toolbar defaults sit behind the same check. The file also turns
  `hibernate.generate_statistics` on, which the `hibernate.*` insights panels need.
  `HibernateStatisticsMeterBinder` binds their meters unless `hibernate-micrometer` is on the
  class path, where Boot's binder registers the same ones.
- `peekaboot-no-push-defaults.yml`. Applies unconditionally, even when Peekaboot itself is
  disabled, to keep telemetry from leaving the process by default. It sets
  `management.otlp.metrics.export.enabled` to `false`, since the starter brings Micrometer's OTLP
  registry and left alone it pushes metrics to `localhost:4318`. Traces and logs need no
  equivalent, because Boot creates their OTLP exporters only once an endpoint is configured.
- `peekaboot-off-defaults.yml`. Applies only while `peekaboot.enabled` resolves `false`, on
  every web type. Sets `jdbc.datasource-proxy.enabled` to `false` (datasource-micrometer
  default `true`), so the JDBC instrumentation the starter brings wraps no DataSource of an
  application that has Peekaboot off. An application that uses datasource-micrometer for its
  own tracing sets the key to `true`.
- `peekaboot-dev-toolbar-defaults.yml`. Applied only when the dev toolbar resolves on. Sets
  `management.opentelemetry.tracing.export.schedule-delay` to `200ms` (Spring default `5s`),
  trading export throughput for latency so a trace is readable in the toolbar while the
  developer is still looking at the page.

`peekaboot-defaults.yml` carries no `management.endpoint.*` property. Peekaboot builds its
endpoint instances itself and borrows only the health bean (see
*In-Process Actuator Invocation*), so none of the application's exposure, access, `show-values`
or `roles` settings decide what the dashboard sees, and Peekaboot has no reason to touch them.
`management.endpoint.health.access=none` still removes the borrowed bean.

`peekaboot-defaults.yml`'s `management.tracing.*` and `management.observations.*` defaults
configure trace sampling and `@Observed` support. Neither decides value visibility. The file sets
no `management.info.*` property: the `info` source adds its four contributors itself (see
*In-Process Actuator Invocation*), so Java, OS and process always show on the Overview and the
application's `/actuator/info` is the same with Peekaboot on and off.

The build card still follows the application's build contributor and git its git contributor,
so `management.info.build.enabled=false` and `management.info.git.enabled=false` narrow the
Overview.

`peekaboot.unmasking.enabled` is the only visibility switch. `MaskingEngine` masks every
sensitive value it sees; `unmasking.enabled` combined with the request's `unmask`
parameter (`ActuatorInsightsService.insightsFor`) is what reveals one. Neither half
suffices alone.

How those sources are contributed matters. Boot moves `defaultProperties`, the source
`SpringApplication.setDefaultProperties` fills, to the end of the environment once every
post-processor has run, so a source merely appended last would outrank it. Where that source
exists Peekaboot folds its entries into it, underneath the application's own, so
`SpringApplicationBuilder.properties("peekaboot.enabled=false")` wins like any other setting.
Where it does not, Peekaboot's named sources are appended last, in this order:
`peekabootDetection` and `peekabootNoPushDefaults` always, then `peekabootOffDefaults` while
Peekaboot is off, or `peekabootDefaults` and `peekabootDevToolbarDefaults` while it is on. One
more source, `peekabootDetectedLaunch`, sits first. It answers no property, so its place gives it
no precedence.

`peekabootDetection` is where all five launch-context-detected values land, since none of them
needs a yml resource of its own (see *Conditional Loading*). Four follow a local run:
`peekaboot.enabled`, `peekaboot.dev-toolbar.enabled`, `peekaboot.storage.enabled` and
`peekaboot.error-page.enabled`. One follows a deployment launch: `peekaboot.security.enabled`
(see *Automatic Dashboard Security*). `PeekabootDefaultsRegistrationTest` pins both halves.

### Automatic Dashboard Security

`peekaboot.security.enabled` follows the same idiom as the other four launch-context switches:
`PeekabootDefaultsEnvironmentPostProcessor` publishes a detected default at lowest precedence,
and any explicit setting wins over it. Unlike the other four the default does not follow
`LOCAL_DEV`; it is `true` on a `DEPLOYMENT` launch and `false` on both `LOCAL_DEV` and `TEST`,
the third value `LocalDevDetector.LaunchKind` grows for this. `TEST` is matched against
`SKIPPED_STACK_ELEMENTS` before the local-dev checks run at all, not folded into them: several
of those checks reject on thread name or class loader alone, so a test that fails one of them
early would otherwise fall through to `DEPLOYMENT` rather than `LOCAL_DEV`. Without the
stand-alone `TEST` check, a consumer's own dashboard tests, and most of this repo's own
integration tests, would start receiving 401s.

The launch kind itself travels in a property source of its own type, `DetectedLaunch`
(`peekabootDetectedLaunch`), never as a property. `SecurityPosture` is built from what it holds,
so it can tell a deployment from a local or test launch after `peekaboot.security.enabled` has
already resolved either way. The source answers no property, so no property file, environment
variable or system property can claim or deny a launch, and it survives the fold into Boot's
`defaultProperties` described above, which the detection source's own name does not.

`DetectedLaunch` extends `PropertySource.StubPropertySource`, which Boot's binder skips, so the
source adds nothing to a `@ConfigurationProperties` bind on a host that never switches Peekaboot
on.

`PeekabootSecurityAutoConfiguration` wires the credential resolution, the credential file (see
*Persisted state* above), the filter registration and the handler-level check (*Servlet Filters*).
It carries the same servlet-and-`peekaboot.enabled` guard as most auto-configurations
above; `peekaboot.security.enabled` gates a nested configuration one level down rather than the
class itself, because the startup posture report is registered whenever Peekaboot is enabled,
not only while the guard is armed: an operator who switched it off on a deployment launch still
gets told at startup that the application's own security must cover `/peekaboot/**`. A developer
who opened a local run with `peekaboot.remote-access.enabled=true` is told that anyone who reaches
the port reads the dashboard.

The banner is logged and not captured: `PeekabootLogbackAppender` drops the listener's logger, so
the plain-text password exists in the application's log and nowhere in Peekaboot's own data.

`SecurityPostureListener` logs the armed guard's block at INFO. It logs it at WARN while the dev
toolbar is on, since the toolbar's requests then raise a credential dialog on ordinary pages, and
while `peekaboot.error-page.enabled` serves a deployment's error page, which has no guard in
front of it. A rejected sign-in logs at DEBUG.

Without a registered guard, `peekabootDashboardAccess` depends on the detected launch. On
`LOCAL_DEV` it is a `LoopbackOnlyAccess`, which admits a request only when all four hold:

- The client address is a loopback literal, parsed with `InetAddress.ofLiteral` and never resolved.
- There is no `Forwarded` header and every `X-Forwarded-For` entry is a loopback literal.
- The `Host` header, or the `:authority` of an HTTP/2 request, names `localhost`, a `.localhost`
  name or a loopback literal.
- `Origin` is absent or names such a host, and `Sec-Fetch-Site` is absent, `same-origin`,
  `same-site` or `none`, or `cross-site` on a GET navigation to a document (a followed link).

Every other request gets a 403 problem from `Refusals`. The rule judges the container's own
request, reached beneath every `ServletRequestWrapper`, so Spring's `ForwardedHeaderFilter`
(`server.forward-headers-strategy=framework`) cannot vouch for another machine. What the container
rewrote itself is out of reach: with `server.forward-headers-strategy=native` Tomcat's
`RemoteIpValve` has replaced the client address and removed `X-Forwarded-For` before the servlet
API hands the request out, and a client in its internal-proxy ranges passes with
`X-Forwarded-For: 127.0.0.1` and `Host: localhost`. The site documents that limit; nothing warns of
it at startup.

`peekaboot.remote-access.enabled=true` swaps the rule for `DashboardAccess.open()`, and
`SecurityPosture.remoteAccessOnALocalRun` says so in one WARN line at startup. On `TEST` and
`DEPLOYMENT` there is no rule: a test drives the application with whatever client it likes, and a
deployment with the guard off is the application's own security's to cover. The switch is read
from the bound `PeekabootProperties.RemoteAccess` and gates no condition, so it is not in
`PeekabootPropertyKeys.BOOLEAN_SWITCHES`.

The same rule answers `permitsDebugDetail`. `DevToolbarAutoConfiguration` and
`ErrorPageAutoConfiguration` read it through `DashboardAccessConfiguration.debugDetailPermitted`,
so the bar and the error page's exception detail go only where the dashboard goes. Without a
`DashboardAccess` bean, where the application excluded `PeekabootSecurityAutoConfiguration`, both
permit.

Failed authentication attempts are bounded, not throttled. A wrong password with the right
username still costs a full 210,000-iteration PBKDF2 derivation, roughly 100ms, and
`CredentialCache` remembers only successful verifications. `DerivationGate` lets two derivations
run at once. A sign-in that arrives while both slots are taken is answered `401` at once and
unchecked, and `CredentialsAccess` logs one WARN the first time.

Checks of the same `Authorization` header share one run, so the dashboard's parallel requests cost
one derivation after the cache's five minutes and none of them is shed. A wrong username is
answered before the gate and takes no slot. There is no per-client state: whoever keeps both slots
busy makes other sign-ins fail until a slot is free, and rate limiting for a deployed application
still belongs at the proxy in front of it.

## Tracing Integration

### OpenTelemetry Bridge

When the OpenTelemetry SDK's `SpanExporter` is on the classpath, `OtelTracingAutoConfiguration`
registers `OtelSpanExporter`. The class condition is on the auto-configuration, not the bean
method.

`OtelSpanExporter` is `TraceStore`'s only span source, so an application without the
OpenTelemetry SDK has a store that exists (`features.tracing`, gated only by
`peekaboot.tracing.enabled`) but can never receive one. `PeekabootAutoConfiguration` publishes
that gap as `features.tracingSpansPossible`. False is a hard guarantee that the bean this section
describes was never created. True only means the bridge exists, not that anything downstream
(sampling, the rest of the host's OpenTelemetry wiring) will actually produce a span. While it
is false the Traces tab fetches nothing and names the dependency to add in place of its list.

The exporter is one more `SpanExporter` bean alongside whatever Boot's own OpenTelemetry
auto-configuration registered, standing up no tracing stack of its own and copying every
finished span it sees into `TraceStore` as well. Turning `peekaboot.tracing.enabled` off leaves
the rest of the app's OpenTelemetry setup (sampling, other exporters such as Zipkin, Jaeger or
an OTLP backend) untouched.

A dashboard request never becomes an observation. `PeekabootRequestObservationPredicate`, an
`ObservationPredicate` bean that Boot applies to the application's registry, rejects the server
observation of every request under `PeekabootPaths.dashboardPath()`. The dashboard's polling
never reaches `http.server.requests`, the http-* insights or the SDK.

The predicate also rejects every observation started while `RequestContextHolder` holds a
dashboard request. Micrometer does not make a rejected observation the thread's current one, so
without that the request's own work (the in-process health check's JDBC calls, Spring
Security's filter-chain observations) would start root traces of their own.
`RequestContextFilter` binds the request before Spring Security's chain runs. A filter working
outside that binding, such as Spring Session's `SessionRepositoryFilter` committing a session
after the chain, can still start root traces for a dashboard request. `RootActionTypes`
classifies most of them as `MAINTENANCE`, which the default view hides.

The exporter still skips the other excluded prefixes (the actuator, the static assets,
`/error`) span by span, judging each SERVER span's path tag against the same `PeekabootPaths`
prefixes the filters and the interceptor use. Only SERVER spans are judged: the prefixes
describe inbound requests, so an outbound call whose remote path happens to start with one (a
health check against another service, say) stays in its trace.

A child of such a request carries no path tag and is stored before the request's own span
arrives, so skipping that span has to undo it. A skipped span without a parent publishes a
`TraceDiscardedEvent`, which `TraceStoreEventListener` turns into `TraceStore.discard`: the trace
leaves all three buckets. A skipped span that continues a remote caller (a probe that sent a
`traceparent` header) publishes one that names the span, and the listener discards only while
everything stored for the trace lies under that span (`TraceDataBundle.liesUnder`), so a request
that called its own excluded path keeps its trace. A skipped span under a local parent publishes
nothing. Everything else becomes a `SpanData` published as a `SpanDataEvent`. Both events go
through `CaptureEventPublisher`.

An error is recorded only for a span whose status code is `ERROR`, as a `CapturedExceptionInfo`
read off the span's last `exception` event (`exception.type`, `exception.message`,
`exception.stacktrace`, the message capped at `CapturedText.MAX_LENGTH` and the trace at
`CapturedStackTraces.MAX_LINES`, like a log's). The status description, capped the same way,
fills in a missing message, and a span that failed without an exception event carries only that.
Every tag value is capped at `CapturedText.MAX_LENGTH`, except `peekaboot.query.parameters`, which
its writer bounds and a cut would break.

`tracing/bridge/otel` is the only bridge. There is no Brave/Zipkin one, so an application wired
to Micrometer Tracing's Brave bridge instead of the OpenTelemetry SDK captures nothing. The
toolbar itself only needs a `Tracer` bean and is injected with either bridge (see
*Auto-Configuration Ordering*).

### Handler and View Spans

`TracingHandlerInterceptor` is the only instrumentation Peekaboot adds to the request path
itself. Outside it, `AsyncTaskDecorator` observes thread hand-offs (see *Async Task Spans*) and
`EventListenerObservationInterceptor` observes event listeners (see *Event Listener Spans*).
`TracingHandlerInterceptor` raises two Micrometer observations, so every exporter the
application has configured sees them, not just Peekaboot's:

| Observation | Raised in | Tags |
|-------------|-----------|------|
| `spring.handler` | `preHandle`, once per dispatch | `handler.type` (low cardinality: the handler class's simple name), `handler.name` (high cardinality: `BeanType.methodName` for a `HandlerMethod`, else the handler class's simple name) |
| `spring.view.render` | `postHandle`, only when the handler returned a resolvable view | `view.type` (always `template`), `view.name` (the view name, or the `View` implementation's simple class name when the handler returned a `View` instance) |

Each sets its `handler.name` or `view.name` as the contextual name, so the span is named
`PersonController.persons` or `persons`, like the async and listener spans, while the
observation name, and so the metrics, stay `spring.handler` and `spring.view.render`.

A `@ResponseBody` or REST controller renders no view, so those requests carry a handler span and
no view span at all.

`preHandle` opens an observation **scope** and stashes it on the request. The scope stays open
across the handler, so observations started inside it (JDBC, HTTP clients) nest under the
`spring.handler` span rather than under the HTTP server span. That nesting is what makes the
overlay's span tree show queries beneath the controller method instead of flat under the
request, and it is the reason to keep a scope rather than just time the handler.

An `ASYNC` re-dispatch is skipped so the handler span is not counted twice, and
`afterConcurrentHandlingStarted` ends it on the thread that started it. A handler exception
skips `postHandle`, so `afterCompletion` ends the handler observation and records the error.
`afterCompletion` receives the exception only where it escapes every resolver. A resolver that
answers it can hand it over with the static `failHandler`, which keeps it on the innermost
dispatch's frame for `afterCompletion`; Peekaboot's error page does that for a 5xx. A frame
without a handler observation, the ASYNC re-dispatch's, has nothing to fail.

The observations belong to a **dispatch**, not to the request, and are held in a stack on the
request: `preHandle` pushes one frame, `afterCompletion` pops one. A handler that returns
`forward:` (or an include) has DispatcherServlet run a second dispatch *inside* the first one's
view rendering, so both dispatches call every callback against the same request. One attribute
per observation would have the inner dispatch overwrite the outer one's view observation, which
is then never stopped. The forward gets no `spring.view.render` span, and its scope stays open on
the request thread. That part reaches beyond the request: every later request that pooled thread
served inherited the leftover context and was captured into that same trace.
`TracingHandlerInterceptorTest` pins the scope, `NestedDispatchTraceCaptureIT` the captured
shape. Nesting is strictly LIFO on one thread, which is what lets a plain stack be right here.

### Async Task Spans

`AsyncTaskDecorator` is a `TaskDecorator`, registered by `AsyncInstrumentationAutoConfiguration`.
It raises one observation when a task crosses a thread hand-off through Spring's task executors:

| Observation | Raised in | Tags |
|-------------|-----------|------|
| `peekaboot.async.task` | the decorated `Runnable`, on the executor thread | `peekaboot.async` (low cardinality, always `true`), `peekaboot.async.thread` (high cardinality: the executor thread's name) |

Its contextual name is `async task`, not the observation name, for the same reason the view
observation above isn't its own row title. Every async entry span carries that same name
because a `TaskDecorator` receives an opaque `Runnable`: the method behind it cannot be
recovered. A telling name has to come from the application, through
`@Observed(contextualName = "...")` on the method, which then shows as a child span.

Two guards, for two different failure modes. The decorator observes only when the submitting
thread already has an observation open; a task with nothing to continue would start a rootless
span rather than extend a trace. And it returns unwrapped the two shapes the scheduling paths
hand a decorator, a `RunnableScheduledFuture` from `ThreadPoolTaskScheduler`'s `decorateTask`
hook and a `DelegatingErrorHandlingRunnable` from `SimpleAsyncTaskScheduler`. One
`TaskDecorator` bean serves both the executor and the scheduler builders, and without that
check Peekaboot's span would parent Spring's own `tasks.scheduled.execution` observation, turning
every scheduled job into an async task.

`AsyncInstrumentationAutoConfiguration` registers the bean as `peekabootAsyncTaskDecorator`, and
its `@ConditionalOnMissingBean` matches that name, not the `TaskDecorator` type, for the same reason
`peekabootTracingInterceptorConfigurer` above matches by name.
`ContextPropagatingTaskDecorator`, which Boot registers when an application sets
`spring.task.execution.propagate-context=true`, is itself a `TaskDecorator` bean; a type-based
condition would treat it as already satisfying the condition and back Peekaboot's decorator off
in exactly the configuration this feature exists to serve. Peekaboot never sets that property
itself.

Both decorators can therefore coexist, folded by Boot into one `CompositeTaskDecorator`. The
fold makes the list's first element innermost, and `ObjectProvider.orderedStream()` sorts
ascending, so the lowest order ends up innermost, closest to the task. `ContextPropagatingTaskDecorator`
declares no order and sorts last, at `Ordered.LOWEST_PRECEDENCE`. Peekaboot's decorator carries
an explicit `@Order(Ordered.LOWEST_PRECEDENCE - 1000)`, placing it just inside that: the trace
context is restored before Peekaboot's span opens, and an application decorator with an ordinary
explicit order still runs inside Peekaboot's observation.

The same decorator bean pool backs both `ThreadPoolTaskExecutorBuilder` and the scheduler
builders, which is why the scheduled-execution guard matters even for an application that never
calls `@Async` directly: Peekaboot's decorator is wired into the scheduler regardless.

Only executors Boot builds receive the decorator: `applicationTaskExecutor` and anything made from
the injected `ThreadPoolTaskExecutorBuilder` or `SimpleAsyncTaskExecutorBuilder`. An executor the
application constructs by hand gets neither it nor context propagation. Boot's
`OnExecutorCondition` skips `applicationTaskExecutor` once the application declares any `Executor`
bean, unless `spring.task.execution.mode=force`.

A task's failure reaches its span by three routes, because two of Spring's paths catch it before
the decorator could see it thrown:

- A task passed to `execute()` throws through the decorator, and `Observation.observe` records it.
- `submit()` on both `ThreadPoolTaskExecutor` and `SimpleAsyncTaskExecutor` hands the decorator a
  `FutureTask`, which keeps the exception. The decorator reads `Future.state()` once the task
  has run and records `exceptionNow()` when it is `FAILED`.
- An `@Async` method runs inside Spring's own `Callable`, which passes a `void` method's failure
  to the `AsyncUncaughtExceptionHandler`. `AsyncTaskFailurePostProcessor` puts
  `AsyncTaskFailureInterceptor` directly behind Spring's `AsyncAnnotationAdvisor`, on the
  executor thread, where it records a thrown exception or a failed returned `Future` on the span
  `RunningAsyncTask` holds for that thread. That covers `void`, `Future` and `CompletableFuture`
  alike. Spring rethrows a `Future` method's failure into its `FutureTask` afterwards, so the
  decorator skips a span that already carries an error rather than recording it twice.

Two paths stay uncovered. `submitCompletable()` completes a `CompletableFuture` from inside the
task `CompletableFuture` hands the executor, so the decorator never holds the future that failed.
`@EnableAsync(mode = AdviceMode.ASPECTJ)` weaves the async hand-off into the class with
`AnnotationAsyncExecutionAspect` instead of a proxy, so there is no advisor chain to join.
Covering it would mean shipping an aspect of Peekaboot's own and requiring the application to
weave it, which the project does not do. Under that mode a failing `void` or
`CompletableFuture` method leaves its span OK; a `Future` method's failure still arrives through
the `FutureTask` route, since the aspect submits through the same `AsyncExecutionAspectSupport`.
A frozen proxy refuses the added advisor and is logged at DEBUG.

Keeping the work under a marked span out of its trace's timings is the store's half of this
feature, in *Async Subtree Timing* below.

### Event Listener Spans

`EventListenerObservationInterceptor` raises one observation per call of an application's
`@EventListener` method, `@TransactionalEventListener` included:

| Observation | Raised in | Tags |
|-------------|-----------|------|
| `peekaboot.event.listener` | the listener method's proxy, on the thread that runs the method | `peekaboot.event.type` (low cardinality: the simple names of the annotation's event classes when it names any, else the simple name of the method's parameter type), `peekaboot.event.listener.class` (low cardinality: the bean's class, fully qualified), `peekaboot.event.listener.method` (low cardinality: the method name) |

Its contextual name is `<SimpleClassName>#<method>`, the row title in the trace view. The names
live in `EventListenerMarker`. Nothing classifies by them: the span is raised only while an
observation is current, so it is always an ordinary child span and needs no `RootActionType`.

`EventListenerInstrumentationAutoConfiguration` registers `EventListenerObservationPostProcessor`,
an `AbstractBeanFactoryAwareAdvisingPostProcessor` like Spring's own `@Async` support, so the
feature needs neither `spring-boot-starter-aop` nor AspectJ. A decorating `EventListenerFactory`
cannot do it: `EventListenerMethodProcessor` initialises only an
`ApplicationListenerMethodAdapter`, through a package-private `init`, so a wrapped adapter never
receives its context. Wrapping the method itself is what makes the span time the real call: a
transactional listener's span opens at after-commit, not at publish, and an event a `condition`
rejects never reaches the method and gets no span.

The advisor's place in the chain is deliberate. The post-processor adds it in front of the
advisors already on the bean (`beforeExistingAdvisors`), so a `REQUIRES_NEW` listener's own
transaction opens inside the span. It runs at `Ordered.LOWEST_PRECEDENCE - 1`, after the
auto-proxy creator (`HIGHEST_PRECEDENCE`) and one step before `AsyncAnnotationBeanPostProcessor`
at `@EnableAsync`'s default order, which then puts the async hand-off in front of Peekaboot's
advisor. An `@Async` listener's span therefore opens on the executor thread, inside the `async
task` span. An application that orders `@EnableAsync` below that gets a span timing only the
submission.

The bean method takes the `ObservationRegistry` as an `ObjectProvider`. Boot configures the
registry in `ObservationRegistryPostProcessor`, a plain `BeanPostProcessor` registered after every
ordered one; resolving the registry while Peekaboot's ordered post-processor is created would
freeze it unconfigured, and every handler the application declares would stop seeing observations.
With two registries and neither primary, Peekaboot cannot tell which is the application's and
falls back to `ObservationRegistry.NOOP`: listeners still run, without a span, and a listener call
never fails over Peekaboot.

What is left alone:

- Beans in `org.peekaboot.backend.`, `org.peekaboot.autoconfigure.` and `org.springframework.`.
  These are Peekaboot's and Spring's own listeners, not the application's work. Captured spans,
  lines and requests reach no listener method, since `CaptureEventPublisher` calls
  `TraceStoreEventListener` directly. Other `org.peekaboot` packages, the testing app's among
  them, are observed.
- Methods the application already annotates with `@Observed`, and classes it annotates with it.
- Static listener methods, which are called without the bean.
- Classes a proxy would break, logged at debug and left wholly unobserved: a final or sealed
  class, one with only private constructors (CGLIB cannot subclass any of them; Kotlin classes
  are final by default), one with a private listener method (Spring refuses to start with one
  behind a proxy, see `AopUtils.selectInvocableMethod`), and one that declares or inherits a
  final instance method that is not private, listener or not. CGLIB cannot override that
  method, so it runs on the proxy instance, whose fields are unset, and returns `null` where the
  bean would return its state. Proxying such a class would make it behave differently only
  while Peekaboot is on.

Like the async decorator, the interceptor observes only while an observation is current. A
listener called with nothing in scope, one on `ApplicationReadyEvent` say, gets no span. An
`@Async` listener runs on an executor thread, so it gets one only when the application sets
`spring.task.execution.propagate-context=true`; Peekaboot never sets it.
`peekaboot.tracing.event-listeners=false` turns the feature off.

Known trade-offs:

- Listener beans become CGLIB proxies, and calling a listener method directly, not through an
  event, also produces a span.
- A proxied listener bean is not the object its own methods see as `this`: an identity check
  against the injected bean fails, and `getClass()` on the injected bean returns the generated
  subclass. Code that needs the declared class should use `ClassUtils.getUserClass`.
- The skip list names packages, not authors. Listeners from third-party libraries outside
  `org.springframework.` are spanned like the application's own.
- A class with a non-private final instance method keeps its listeners unspanned, even when
  that method never touches a field.

### Micrometer Tracer Integration

On the request path (`RequestCaptureFilter` and `DevToolbarFilter`) Peekaboot reads the trace
context from Micrometer's `Tracer` API, via `tracer.currentSpan()`. That keeps the filters
compatible with Boot's tracing auto-configuration whether or not MDC propagation is enabled.
Log capture is the exception: `PeekabootLogbackAppender` runs inside Logback, where the only
trace context available is the event's frozen MDC map, so it reads `traceId`/`spanId` from
there and depends on Micrometer's MDC propagation (Boot's default) being on.

Peekaboot only ever *reads* trace context. There is no `Propagator`, no `TextMapPropagator` and
no header-injection code anywhere in the repo.

### Log Capture

A captured line takes the path every capture event takes (see *Tracing Flow*), and
`TraceLogMapper` maps a trace's lines into its flat log list on read (see
*Trace Assembly and Enrichment*). One logger is left out: `SecurityPostureListener`'s, which
writes the startup security report and with it the generated dashboard password. `append`
returns for it before anything is read, so the line reaches the application's log and never the
store, `/peekaboot/api/logs` or the trace journal.

A line's message is capped at `CapturedText.MAX_LENGTH` (16,000 characters) and then ends with
`... N characters omitted`. The cut ends before the last whitespace within 1,024 characters of the
limit. Where that stretch holds no whitespace it ends before the last quote or comma there, unless
an `@` follows before the limit, which keeps a URL's credentials together for the mask. A text
with no such boundary in that stretch is cut at the limit.

The appender is attached by the `LogbackAppenderRegistrar` bean, which
`TraceCaptureAutoConfiguration` registers while tracing is on, whatever the toolbar switch says.
Capture starts when the registrar bean is created, so lines the beans created after it log at
startup are captured as untraced lines.

Capture stops at `ContextClosedEvent`, before the context destroys any bean, so the store takes
no line a bean logs while it is destroyed.

A line without a trace id, written at startup, on a thread outside any trace, or by Tomcat after
a request's trace ended, goes into the `UntracedLogRing`, the newest
`peekaboot.tracing.max-untraced-logs` (1000) beside the buckets. It belongs to no bucket and fails
no trace, whatever its level. `TraceStore.addLog` stays the one entry point, so whatever replays
the store's input replays these lines too.

The ring takes its own lock to add a line and to copy
itself for `untracedLogs()`, and no bucket read shares it. A line can arrive, or a trace can enter
a bucket, between a read of the buckets and a read of the ring. With several contexts in one JVM,
as in a test suite, every context's appender hangs off the one root logger, so each context's
ring also holds the other contexts' untraced lines.

`GET /peekaboot/api/logs` searches every line the store holds (`LogSearchService`). One request
reads the newest 10,000 of the lines its other filters admit (`MAX_LINES_READ`) and hands out a
`next` where older ones are left. Its
candidates are each bundle any bucket lists (`TraceStore.storedTraces()`, every bucket read under
its own lock), one `capturedLogs()` read per bundle with one span resolver, and one read of the
untraced ring. The store numbers each line it keeps (`LogCapturedEvent.sequence`, one count
across traces and the ring), and that number orders the search newest first and is what its
cursor names. Each candidate is mapped and masked once, by the same `TraceLogMapper.map` a
trace's logs go through, and only until the page is full, since `q` reads the masked text. A
search that stops at the 10,000th line names the oldest line it read, so a page can be short or
empty and still carry a `next`.

The store numbers a line before it files it, so threads that log at once can file lines out of
number order. A line filed while a search pages through can then show up on a later page, below
the cursor, or miss that page and every later one.

Capture is off for as long as any application in the JVM is re-initialising Logback. Boot does
that on the first start in a JVM and on the first start after any application closed or failed
to start. From `ApplicationStartingEvent` it denies every log event JVM-wide, then resets the
logger context, which detaches the appender. `LogbackCaptureReinstaller` reattaches it once
logging is initialised, but a request another application served in between is traced with no
logs against it. One application never sees this, since its re-initialisation happens before it
serves anything, so this belongs to test suites, where contexts start while other contexts serve
requests; the testing app's suite keeps Logback configured for the life of its JVM (see
`docs/TESTING.md`). The product cannot close the window from outside Logback: Boot stops the
logger context before resetting it, which drops even reset-resistant listeners.

### Trace Assembly and Enrichment

The API serves a trace as a `TraceResource`, a `TraceSummary` and a `TraceDetail` (`summary`
and `data` on the wire), from `GET /peekaboot/api/traces/{traceId}` and as each row of
`GET /peekaboot/api/traces`, where `data` is null unless the request says `view=full`. A
`view=full` page holds at most 100 rows (`PageLimit.MAX_FULL`), the default `limit`. A larger
`limit` is refused with a 400 on `limit`, after the range check every `limit` gets.

`TraceRows` builds both in three steps, and the split is easy to get wrong. `TraceTreeMapper`
builds the tree and nothing else.

1. `TraceTreeMapper.map(traceData, content)` builds the `TraceTree` from a
   `TraceDataBundle.snapshot()`: the spans in their places, the root action type, the request
   off the root, its path masked (`TraceRequests`), and the span and query counts. What a span
   carries beside its place and its timing comes from the `SpanContent` it is handed, which
   `SpanContents` builds. A `view=full` row maps with `served(traceData)`: tags, exceptions and
   query text masked, statements laid out, row counts paired. A summary row maps with
   `forSummary()`: the exception masked and folded, a statement as its masked text alone, no row
   count, and the tags as stored, since a summary serves none. `/routes` maps with
   `forIssues()`: the tags as stored, no exception, no row count, and a statement as its masked
   text alone, so the statements that repeat in a summary repeat here. Only a set of issue types
   leaves that mapping. An async row takes
   `TraceTreeMapper.mapSubtree` instead, which counts its own subtree's spans and queries and
   names no request. Every `SpanNode.issues` list is empty.
   **It attaches no issues and correlates no logs.**
2. `IssueDetector.detectIssues(tree)` fills those issues in.
3. `TraceRows` reads what only the stored bundle knows into `TraceFacts`, the logs and their
   dropped count in one read under one lock (`TraceDataBundle.capturedLogs()`), and
   `TraceSummaries` turns the tree and the facts into the summary, its issues in tree order.
   The summary is built naming no bucket. `TraceReads` sets the ones `TraceStore.bucketsOf`
   names after the mapping (`TraceResource.withBuckets`); the error digest records its summary
   without any. `TraceDetails` builds the data only when the view asks for it, the
   `HttpExchange`'s path masked. With the data, `TraceLogMapper` maps every log once and the
   reasons take their ERROR entries by index (`TraceLogMapper.errorsAmong`). A log's span
   resolves through redirects that move while spans arrive, so a row resolves each span id once
   (`TraceLogMapper.resolvingOnce`) and its subtree, its reasons and its list agree on it.

`TraceTree` is internal. It is what steps 1 and 2 hand on and is never served as it is. Step 2
copies the mapper's output through `TraceTree.withSpans` and `SpanNode.withIssues`, so it names
only what it adds.

`TraceLogMapper` masks each log's message and stack trace by value shape, the trace through
`WireStackTraces`, and resolves each log's `spanId` through the bundle's duplicate redirects. A
failed span's `exception` goes through the same `WireStackTraces`, so its stack trace is masked
whole and then folded exactly as a log's is. An async row counts and lists only the logs
`TraceLogMapper.logsWithin` finds in its own subtree, and its data lists only the queries inside
it, filtered before extraction so a statement outside it is never formatted.

A complete whole trace is `ERROR` when the store's sticky failure flag (`TraceData.failed`) is
set or one of its reasons is a failure. An async row ignores the store's flag, which covers the
request that dispatched it, and fails on its own reasons alone. Its buckets and dropped counts
are its trace's.

A trace that is not complete reads `IN_PROGRESS` while its run is in progress and `INTERRUPTED`
once a later run began (`TraceData.interrupted`), since the process its root belonged to has
stopped. Only a restore begins runs (`RestorableTraceStore.beginRun`), so only a restored trace
can be interrupted. `beginRun` also raises the creation order of every span of the new run past every
order stored before it (`SpanData.numberedAfter`): the exporter numbers spans from 1 in every
application context, and without the raise every live position would sort below the restored ones.

The store files a trace in its buckets in the same step that stores the span or the ERROR line
that files it, under the bundle's span lock (`TraceDataBundle.addSpan(span, fileInBuckets)`).
Every reader maps the trace first and reads its buckets afterwards (`TraceReads`), so a response
never names fewer buckets than its data puts the trace in. Locks nest in two orders: a bundle's
span lock, then Errors' or Slow's monitor (`classify`, `addLog`); and All's monitor, then Errors'
or Slow's (`resolveBundle` looks the trace up in them inside All's `computeIfAbsent`). Nothing
waits for anything while it holds Errors' or Slow's, and nothing takes All's monitor under a span
lock, so the nesting cannot deadlock
(`InMemoryTraceStoreTest.nothingWaitsForABundleWhileHoldingABucket`).
A span arriving between the mapping and the bucket read can still name a bucket the mapped data
does not explain yet, an async entry can take the trace out of Slow, and a full bucket can evict
it. The next poll agrees.

`TraceDataBundle.snapshot()` reads everything the mapper needs under one lock: the spans in
creation order, the root span, the `complete` and `failed` flags, the trace window and the dropped span count.
The root is chosen there and nowhere else: the earliest-created stored span without a parent,
else the earliest-created stored span continuing a remote caller's trace (`SpanData.remoteParent`,
its parent not stored), else none. Spans export as they end, children first, so a trace with
neither is a request still running and none of its stored spans is its root.

`TraceData.complete` and `ListingRoots.fragment` come from that same definition. A trace is
complete while it has a root, and stays complete once a span without a parent was stored, so a
root the span cap evicts later leaves the trace complete with `rootSpan` null. A fragment is a
bundle that stores spans but is not complete. A root continuing a remote caller's trace keeps the
trace complete only while it is the root, never stickily, because of the self-call window below:
there the remote parent is the application's own CLIENT span, and its arrival must make the trace
read in progress again.

An application calling itself over HTTP gets the rule wrong for a moment. The inner SERVER
span's remote parent is the application's own CLIENT span, so the trace reads complete between
the inner span's export and the CLIENT span's, in progress again once the CLIENT span arrives,
and complete once the real root does.

`TraceDataBundle.listingRoots()` answers the same rule without a copy, which is what the listing
filters classify by, so the row a filter admits and the tree the mapper builds agree on what
started the trace. `attachOrphansToRoot` then re-parents every other span
whose parent is not in the trace onto that root, so a subtree whose parent has not been exported
yet does not silently vanish. Without a root, `mapOrphans` serves each span whose parent is not
stored as the head of one `TraceTree.orphans` subtree, earliest start first. A trace that is
nothing but a cycle has no such span, so its earliest-created span heads the one subtree, its
parent edge cut to end the walk. A trace without spans takes the same path and has neither root
nor orphans; its start and duration are the window the store holds, which is empty unless a
replayed remnant carried one. A complete trace whose root the cap evicted is served the same
way: `complete: true`, `rootSpan: null`, every stored span under `orphans`. With a root,
`orphans` is `[]`. `TraceSummary.outcome` is `IN_PROGRESS` while the trace is not complete (`INTERRUPTED` once its
run ended, see above), else `ERROR` or `OK` by the verdict above. What moves the window is in *Async Subtree Timing*. The
dropped span count travels on the snapshot rather than being derived from the span list: the
list is already deduplicated and already capped, so nothing in it can say whether real spans
were dropped. The summary serves the count as `counts.droppedSpans`.

`InMemoryTraceStore` opens a bundle on the first span, log line or `RequestCompletedEvent` it
sees for a trace id. The request usually comes first: `RequestCaptureFilter` publishes it as the
response finishes, while spans wait for the exporter's batch. So
`GET /peekaboot/api/traces/{traceId}` for a request just served can answer `200` with
`complete: false`, `outcome: IN_PROGRESS`, every count zero, and `rootSpan: null` and
`orphans: []` in its data. A `404` means the store has never seen the id. The toolbar's fetch schedule treats a
trace that is not complete like a `404` and tries again (see *The collapsed bar's trace fetch
schedule* in [`peekaboot-frontend/README.md`](../peekaboot-frontend/README.md)).

### Route Statistics

`GET /peekaboot/api/routes` folds the All bucket's complete HTTP requests into one row per method
and route pattern (`RouteAggregation`). It reads each bundle's listing facts and maps no summary.
A server root without any HTTP tag names no request, so no row counts it.

Every row keeps the route tag as recorded. Under Boot's default static-resource mapping
(`spring.web.resources.add-mappings`), the static-resource handler maps `/**`, so a GET no
controller matched lands in the `GET /**` row, and an unmapped POST lands in `POST /**` with a 404.
The `route: null` row holds the requests recorded without a route tag, or with only a placeholder
such as `NOT_FOUND`.

Every number on a row comes from the listing facts, which say what the summaries the listing
serves say. `errorCount` counts the `ERROR` outcome (`TraceFilter.outcomeOf`), `latestSlowTraceId`
names the newest request at or above the slow-trace threshold, the rule of the `SLOW` reason, and
`issueCounts` counts the requests carrying each issue type.

Only the issue types need the spans: `TraceResources.issueTypes` maps the trace with
`forIssues()`, which masks only the statements and reads no log, and runs the `IssueDetector`.
No bucket is read. The percentiles are nearest rank over every request, as `AggregateStats`
computes the Insights ones.

The window covers the complete HTTP requests All holds at the moment of the call. Nothing is kept
between calls.

### Span Deduplication

Deduplication runs primarily on write, in `TraceDataBundle.addSpan`. As each span arrives,
`SpanDuplicateMatcher.isDuplicate` collapses a child span into its parent when both are datasource
spans, both failed or neither did, and they share a name and their tags match once `peer.service`
and `jdbc.datasource.name` are ignored. A datasource span is one that carries a tag key starting
with `jdbc.` or `db.`, which every datasource-micrometer convention and its OpenTelemetry query
convention list. That is the shape a single JDBC call instrumented by two datasource decorators
produces.

A method that calls itself under `@Observed` keeps its nested spans, and a twin that failed under
a parent that did not is kept with its exception, so the trace still fails and still says why in
either arrival order. The removed span's own children are re-parented onto the nearest surviving
ancestor so the tree stays connected.

The redirect table, its reverse index and the children index live in `DuplicateFolds`, which one
bundle owns and calls under its span lock. A span whose parent id is its own id is refused with an
`IllegalArgumentException` (`span <id> names itself as its parent`) before the store numbers it
or gives its trace a bundle: it would fold into itself and its redirect would never resolve. A
restore counts such a line among the ones the store refused, and the next fold leaves it out.

`peekaboot.tracing.max-spans-per-trace` (default 500) then caps the already-deduplicated span
count, so the cap counts real, distinct work rather than a double-instrumented call as two
spans against it. A trace still over the cap drops its oldest spans to make room and counts
them in `summary.counts.droppedSpans`, on both `GET /peekaboot/api/traces` and
`GET /peekaboot/api/traces/{traceId}`. A count above zero shows as a `TRUNCATED` badge in the
trace list and the detail overlay. The cap is at least 1: a lower value fails the binding
(`peekaboot.tracing.max-spans-per-trace must be at least 1 (0 configured)`) and the application
does not start.

The fold checks both arrival directions on every insertion (see `TraceDataBundle`'s class
Javadoc for why it has to), but `addSpan` returns as soon as the arriving span duplicates its
own already-stored parent, without re-examining that span's children. On a triple-nested chain
whose three spans all duplicate one another, two of the arrival orders therefore leave one
duplicate standing. Two independent properties keep that shape out of reach, either sufficient
alone. It needs three nesting levels, where `SpanDuplicateMatcher.SERVICE_IDENTIFIER_KEYS`
carries one key per datasource decorator and only two decorators exist. And it needs a span to
arrive after its own parent, which the `BatchSpanProcessor`'s child-before-parent end ordering
forbids. Adding a third service-identifier key, or making `SpanDuplicateMatcher.isDuplicate`
non-transitive, brings it within reach. The datasource test holds per span and the failure state
is an equality, so neither breaks transitivity. The answer to that is a few lines in
`TraceDataBundle.addSpan` and `DuplicateFolds`, never a second deduplication pass beside the
write-time one.

### Async Subtree Timing

Background work must not lengthen the trace that triggered it, so `TraceDataBundle` keeps two
windows rather than one, each a `Window` record of earliest start and latest end. `fullWindow`
stays the true window over every span ever stored. The second covers only the spans outside an
async subtree. It lives in `AsyncMembership`, the bundle's collaborator that also holds which
spans are async, and `synchronousWindow()` is an O(1) read off it. It falls back to the full
window for a bundle whose every span is async: an orphaned task, where the background work is
the whole trace and its own duration is the honest answer.

`snapshot()` passes `synchronousWindow()` as `TraceData.duration`, so the listing, the detail
header and the waterfall denominator in `trace-detail/tabs/spans.js` all read it, and
`InMemoryTraceStore` admits to the Slow bucket by the same method.

Eviction never lowers either window, so a trace keeps listing the duration the Slow bucket
admitted it by once the span cap has dropped its earliest spans. The synchronous window's
recomputation therefore starts from the window of every span the cap evicted while it counted as
synchronous, never from nothing. A window recomputed from the stored spans alone would shrink as
spans are evicted and take the trace out of Slow on lost spans. Two arrivals do lower the
duration, and the Slow bucket follows it down. Only a trace the store filed in Slow is looked for
there: the bundle remembers it, so the spans of a trace that never ran slow never take the Slow
bucket's monitor. A late async entry shows that spans already
counted were background work. The first synchronous span of a bundle that held only async spans
ends the fallback to the full window, so an orphaned task's minutes give way to the request that
dispatched it.

`TraceSummary.Counts`' `spans`, the query figures and the whole trace's outcome deliberately
cover every span, async ones included: Errors-bucket admission reads the bundle's own error signal, which any span
sets, so a trace whose only failure was a background task would read OK while sitting in the
Errors bucket.

Membership lives in `AsyncMembership`, maintained span by span in `TraceDataBundle.addSpan`. An
arriving span enters it when it carries the marker or its resolved parent is already in it, and
folds into the synchronous window otherwise. A span that enters takes its stored descendants
along. Spans are stored child-before-parent, so a marked span's descendants have been counting as
synchronous until it turns up. The walk follows `DuplicateFolds.resolvedChildIdsOf` down from the
entering span, which also returns the children of each duplicate folded into it, and stops at
spans already in the set, whose own descendants entered with them. Each span is walked once, and a
trace with no marked span pays nothing. A min/max pair cannot drop one span, so the synchronous
window is refolded over the stored spans only when a span on one of its edges leaves it. That
happens when a stored span joins a subtree, and when an arriving span enters one after absorbing
a duplicate that counted as synchronous. A survivor that stays synchronous lies around its
duplicates and covers their time. `listingRoots()` reads the subtree entry points off the same
set, a member whose resolved parent is not itself a member, which keeps the listing pass off a
tree walk. It reads the root under the same lock, so a request span arriving between the two
reads cannot make an orphaned entry pass for the trace's root and lose its row.

Each entry point is a listing row of its own, and the listing is newest first by arrival, the
order the buckets already keep. A trace's row dates from when the trace entered the bucket; an
async row from its entry span's export, since that is when the row appears. Placing it beside its
trigger instead would bury a task that outlived its request by minutes under every trace that
arrived meanwhile, off the first page exactly while the user waits for it. To compare the two,
each bucket stamps a trace on entry with the newest `creationOrder` the store has received
(`ListedTrace.listedAt`) and with a store-wide entry number (`ListedTrace.sequence`). A row's
`RowPosition` is that stamp, for an async row the later of its entry's `creationOrder` and its
trace's stamp, then the entry number, then the trace's own row ahead of its async rows in entry
order. That order is total, and no part of a position changes while the row stays in its bucket,
so a trace that entered a bucket after its task ended still lists above its own async row, and the
listing's `cursor` can name a position. A page lists what sorts after the cursor, so no older row
is listed twice or skipped while rows come and go, and a trace that enters a bucket again moves to
its top. A request's own row appears when its root ends, at the position its first span or log
line gave the trace, so it can list below a page already served. Every cursor also carries the
store's `numbering`. Each JVM draws a random first numbering and counts up from it for every store
it creates. A restart creates a new store and renumbers every row, so a cursor from before it is
refused with a 400 on `cursor`.

Ancestry resolves parents through `DuplicateFolds.resolve(...)`, the redirect table span
deduplication leaves behind, exactly as `rootOf` does. A span hanging off a deduplicated span
names a parent id that is no longer stored, so skipping the redirect would leave it outside the subtree it belongs to and back
in the synchronous window. A duplicate folded into a span that is already async brings its stored
children into the subtree the same way.

The trade-off is truncation. A span evicted while it counted as synchronous stays counted, even
if its async entry arrives later and would have taken it out. So a truncated trace can stay in
Slow on background work, but never drops out on lost spans. A member stays one after the entry
above it is evicted, and lists as an entry point of its own. A child arriving after its entry was
evicted folds into the synchronous window instead: nothing stored says the entry existed, and
remembering evicted ids would grow without bound. The dropped span count already says the data is
partial, and 500 spans ahead of the async entry point is well outside the case this serves.

### Query Extraction

Database queries aren't captured specially. A query shows up in a trace because the
JDBC/datasource instrumentation on the classpath already emits a span for it, tagged with
`db.*` or `jdbc.query*` attributes. That instrumentation is
`datasource-micrometer-spring-boot` plus `datasource-micrometer-opentelemetry`, which
`peekaboot-spring-boot-starter` depends on. An uninstrumented DataSource emits no query
spans, and nothing downstream can tell that apart from an endpoint that runs no queries, so
the starter carries the instrumentation rather than leaving both cases reading as `0`.
While `peekaboot.enabled` resolves `false` that instrumentation is switched off, through
`peekaboot-off-defaults.yml` (see *Default Properties*).
`DbSpans.isQuery` is the one definition of a query span:
the CLIENT side of a database call carrying a `db.*` or `jdbc.query*` tag. `jdbc.*` alone is
not enough, since datasource-proxy's connection and result-set spans carry
`jdbc.datasource.name`/`jdbc.row-count` and are not queries. The predicate is shared by
`TraceTreeMapper` (`summary.counts.queries.count`), `IssueDetector` (SLOW_QUERY) and
`QueryExtractor` (the `queries` list), so the three numbers a trace reports about its queries
are one number; `TraceTreeMapperTest` pins the equality.

`QueryExtractor` builds each trace's `queries` list from those spans, independently of the span
tree's own names, one entry per query span. Each entry carries the `statement` its span carries,
so an agent reads a query's SQL without joining on `spanId`. A span whose instrumentation recorded
no statement is listed all the same, with a null `statement`. `DbSpans.sql` checks tags in priority order. The site states the
outcome under [the trace view](https://www.peekaboot.org/docs/traces/#the-trace-view);
the order and the reasons are here:

1. `db.query.text`, the current OpenTelemetry semantic convention, emitted by
   `datasource-micrometer-opentelemetry`, which the starter brings
2. `db.statement`, that convention's superseded spelling, so a library emitting both is read
   by the current one
3. `jdbc.query[N]` (datasource-proxy/Micrometer)
4. only if nothing tagged the span, its own name, and only if that looks like SQL

`SqlStatements` builds one `SqlStatement` per query span. `TraceQueries` keeps it the first time
it is read, so `SpanNode.statement` and `QueryInfo.statement` are one object and each statement is
formatted and masked once per response. A summary row builds no `TraceQueries`.
`SqlStatements.textOnly` gives it the masked text the REPEATED_QUERY issue compares and serves,
with no layout and no parameters. `text` is the
masked SQL. `formatted` is the raw SQL laid out by `HibernateSqlFormatter` (Hibernate's `format_sql`
style, `FormatStyle.BASIC`, each statement of a batch on its own) and then masked.

`formatted` is null without Hibernate on the classpath, the formatter bean's condition, and when the
formatter throws, which `BasicFormatterImpl` does on unbalanced closing parentheses. `parameters` are
the masked literals of `peekaboot.query.parameters`, empty when the tag is absent or malformed.
`omittedParameterSets` is the count of `peekaboot.query.omitted-parameter-sets`, 0 when that tag
is absent or not a count. Formatting runs before masking so masking sees the values it sees in the raw text.

The statement tags and the parameter tags are dropped from `SpanNode.tags` rather than served a
second time beside the statement. A summary's tree keeps them and is never served. A
datasource-proxy result-set span's `jdbc.row-count` tag is served parsed as `SpanNode.rowCount`
(null when it does not parse), on the query span it belongs to. `RowCounts` pairs the two by
creation order, and both mappers read that one pairing through `TraceQueries`.

The Spans tab therefore reads facts the backend decided instead of
re-deriving them from tag and span names. `DbSpans.system` mirrors this priority for
`db.system.name` / `db.system` / `jdbc.datasource.name` / `peer.service`.

`QueryInfo.dataSourceName` tells two DataSources on one engine apart in the Queries tab
(`POSTGRESQL · orders-db`). It is the Hikari pool name (`jdbc.datasource.pool`), else
datasource-proxy's `jdbc.datasource.name`, which is often the database catalog. A pool name
Hikari generated (`HikariPool-1`) is skipped, as datasource-micrometer skips it, so a
DataSource without a `pool-name` falls through to the datasource-proxy name. Setting
`pool-name` on every DataSource makes the label predictable.

Masking is value-patterns only, not column-aware literal masking (`MaskingRules.VALUE_PATTERNS`
carries the reasoning), so a credential with no provider-recognisable shape sitting in an
ordinary column is not caught. The [security page](https://www.peekaboot.org/docs/security/#masking)
states that as a caveat and tells readers to assume a captured trace carries plaintext SQL. It
is a caveat, not a promise waiting to be strengthened.

Bind parameters are Peekaboot's own capture. The OpenTelemetry convention tags none, and
datasource-micrometer's `jdbc.params[N]` is one comma-joined string. `QueryParameterObservationFilter`
reads datasource-proxy's `QueryInfo.getParametersList()`, which datasource-proxy fills for every
prepared statement whatever `jdbc.datasource-proxy.include-parameter-values` says.

It adds them as one high-cardinality key, `peekaboot.query.parameters`
(`QueryParameterTags.PARAMETERS_TAG`): a JSON array of parameter sets, one per batch entry, each
an array of SQL literals (`SqlLiterals`).
Values bound by name are skipped, and a statement without parameters gets no key. A filter runs in
`Observation.stop()` before any handler's `onStop`, so the tracing handler tags the span with it.

The key sits on the real span, unmasked. An application that also exports its traces, over OTLP
say, exports the bind values with them; masking applies only where Peekaboot serves a trace.

Excluding `org.peekaboot.autoconfigure.QueryParameterAutoConfiguration` via
`spring.autoconfigure.exclude` turns bind-value capture off, leaving every other Peekaboot
feature untouched.

Three caps bound what a single query can add. `SqlLiterals.MAX_LITERAL_LENGTH` (1000) cuts a
literal past that many source characters, or bytes for a `byte[]`. A `byte[]` is capped before
hex-encoding, so the cap bounds the source data rather than the doubled hex output. The cut
literal ends with the count dropped as a trailing SQL block comment,
`'...' /* N characters omitted */` or `X'...' /* N bytes omitted */`, singular for a count of
one. A truncated literal
therefore stays one well-formed literal followed by a comment, rather than a closed string
trailing bare, unquoted text. A literal must keep that shape if it is ever substituted into
query text in place of a placeholder.
`QueryParameterObservationFilter.MAX_PARAMETER_SETS` (100) drops the parameter sets of a batch
past that many entries rather than rendering and serialising all of them. It records how many
it dropped as a second key, `peekaboot.query.omitted-parameter-sets`
(`QueryParameterTags.OMITTED_PARAMETER_SETS_TAG`), absent when it dropped none. The statement
serves that count as `omittedParameterSets`, and the browser words it.
`QueryParameterObservationFilter.MAX_PARAMETER_CHARACTERS` (16,000) bounds the literals of one
statement together. A batch past it keeps the leading sets that fit and counts the rest in the
same key. A first set past it keeps its leading literals and ends with
`/* N parameters omitted */`.

The caps exist because the capture is always on, unlike datasource-micrometer's own
`jdbc.params`, which is off by default. `OtelSpanConverter` passes this tag whole, since a cut
would break its JSON, so nothing after the filter bounds it. An unbounded literal, set or batch
would cost time on the application's query thread, bloat the in-memory trace store, and ride along
on any OTLP export.

Two pipelines render a query and only one depends on `QueryExtractor`. The Spans tab names each
row by `span.name`, OpenTelemetry's own span-name summary such as `SELECT customer_order`, and
shows the statement in the span's query panel. The Queries tab lists each query with its own
`statement`, the one its span carries. Both render it through `shared/sql-view.js`, which
substitutes the parameters in the browser. The overlay opens on Spans, so `ScreenshotCapture`
clicks across to Queries for the one shipped image of `QueryExtractor`'s output.

## Data Models

### Trace Domain

What the API serves is declared on the records under `domain/trace`, `domain/log` and
`domain/errors` and in `openapi.json`, and `OpenApiContractIT` holds the document to what the
running application answers (see *API contract*). When a component is null and how a list is
ordered is on each record's Javadoc.

What the store holds is `TraceData` and its `SpanData`, read out in one go by
`TraceDataBundle.snapshot()`. Their Javadoc says which flags are sticky and when the root is
null; *Trace Assembly and Enrichment* has the rules behind them.

### The insights data

`GET /peekaboot/api/insights/data` is `InsightsDataReader`, a bean of its own beside
`InsightsService`, which reads the rings through the collector. `SeriesRings.levelData` copies
only the samples taken at or after the request's `since`, counted under the rings lock; the
newest keeps its date, so every timestamp still derives from `endEpochMs`, `intervalMs` and
`count`.

Each series gets a `SeriesSummary` over that window: `AggregateStats.of` over level 0's samples,
and `AggregateStats.ofAggregates` over the windows above it. There the median and the p95 are
nearest rank over the windows' own median and p95, each window counted once per sample, while
the extremes and the average stay exact. A window of fewer than 20 samples has its maximum as its
p95, so at level 1 of the default levels (six samples a window) the p95 never falls below the true
one. The full view adds the samples and a `timestamps`
array; the Insights tab asks for it, since it charts every sample.

`InsightsProblems` checks the window against the eight `ProblemRule`s over every series,
whatever `series` narrows, and both views list what it found. A rule reads the bundled panels'
series ids and is skipped where one of them is absent or holds no sample; `SLOW_HTTP` shares the
slow-trace threshold with the Slow bucket.

### The insights SSE stream

`InsightsSsePublisher` fans the collector's ticks and roll-ups out over
`/peekaboot/api/insights/stream` to every dashboard that has its Insights tab on screen. The tab
closes its stream when the reader leaves it and opens a new one on return, re-reading every level
it had loaded. A tick carries series values only; tiles are read from
`/peekaboot/api/insights/config`, not streamed. One dispatch thread renders each event once
and offers it to every `Subscriber`'s bounded send lane, whose own sender thread performs the
blocking write, so one wedged peer drops its own events instead of stalling the stream. A peer
whose lane overflows is dropped and its stream completed, so its browser reconnects: by the
dispatch thread while no send is in flight, otherwise by the peer's own sender once its send
returns. The
same dispatch thread sends the heartbeat: it polls the event queue with a 15-second timeout,
and a poll that comes back empty means the stream has been idle that long and gets a
keep-alive comment instead. A busy stream never needs one. The publisher refuses past
`MAX_SUBSCRIBERS` with a 503.

Emitters carry a thirty-minute timeout (`EMITTER_TIMEOUT`). It only reclaims a peer that
vanished without closing its socket, since the heartbeat and the lane overflow already detect
one that is merely wedged.

On context shutdown the publisher stops the dispatch thread, interrupts every sender and then
completes each emitter. An interrupt cannot end a write already inside the container's socket
call, so `completeUnlessSendingWithin(STOP_GRACE)` waits 200 ms for that write and detaches the
peer rather than blocking shutdown behind it. A sender that was detached, at shutdown, on a
timeout or on a drop, ends after the send it is in, also when the container's write consumed the
interrupt.

### Insights Domain

`ActuatorInsightsResponse` carries one record per dashboard section, declared under `domain/`
and in `openapi.json`. What the records do not say:

`LiquibaseInfo` flattens every `SpringLiquibase` bean of every context into one list sorted by
`orderExecuted`; Liquibase records no execution time, so unlike `MigrationInfo` it carries none.

`RuntimeInfo.machine` (`MachineInfo`) and `RuntimeInfo.process` (`ProcessInfo`) are each computed
once and cached, since their values are static for the JVM's lifetime, and neither forks a
process: the CPU model and topology come from `/proc/cpuinfo`, the real uid and gid from
`/proc/self/status`. That file is read rather than a directory stat'ed because no file's owner,
the working directory's included, reliably shares the credentials the process runs under.

Every fact is best-effort: whatever the host doesn't let the JVM read stays `null`/empty and the
dashboard renders no row for it. The JDK's processor count and total memory are container-aware,
reporting a limited container's share rather than the host's. The machine's reverse hostname
lookups run in parallel on virtual threads against a shared ~1s budget; one that misses the
budget, or only echoes the literal address, leaves the hostname `null`.
`ContainerRuntime.current()` is the one container detection in the codebase, and
`LocalDevDetector` consumes the same cached result.

`dataSources` comes from `DataSourceMetadataList`. A read opens a real `Connection` for its
`DatabaseMetaData` and parses the URL that reports. `databaseProduct` is therefore what the *URL*
names, so a MariaDB reached over a `jdbc:mysql:` URL reports MySQL; `databaseProductName` is the
driver's own answer and can disagree.

No read runs while the context refreshes. The ready banner is the first reader and waits at most
two seconds; every later reader gets what has been read so far and never waits. Each read runs on
a virtual thread named `peekaboot-datasource-metadata`. A read that fails, with an exception or an
`Error`, is logged at WARN the first time and at DEBUG after that, costs the DataSource its card
and nothing else, and is repeated by the next reader once 30 seconds have passed. A DataSource
that has answered is never connected again.

## Testing

Test categories, each module's suite and how to test an auto-configuration are in
[`TESTING.md`](TESTING.md#suites).

## Key Design Decisions

1. **No external dependencies for tracing**: works without Zipkin, Jaeger or other collectors
2. **Micrometer-based**: Micrometer's `Tracer` API for trace context on the request path; only the Logback appender reads MDC (see *Micrometer Tracer Integration*)
3. **Capture stays off the application's events**: the exporter, the appender and the request filter publish to `CaptureEventPublisher`, which calls `TraceStoreEventListener` directly. An application's listeners never hear a captured span, line or request, the event-listener instrumentation never spans one, and an async multicaster cannot reorder them. From Spring's event bus the store's listener takes `ApplicationReadyEvent` and the appender's registrar `ContextClosedEvent`, nothing else
4. **Bucketed storage**: three insertion-ordered maps, each capped at its own size and evicting its oldest trace once full. Errors and Slow hold references to the same bundles as All, so a qualifying trace outlives its own eviction from All. `max-traces` is at least 1; `max-error-traces` and `max-slow-traces` may be 0, which lists no trace in that bucket while the trace still says it failed or ran slow. A value below the floor fails the binding and stops startup. [Traces](https://www.peekaboot.org/docs/traces/#the-three-buckets) has the bucket sizing and the slow-trace threshold, [HTTP API](https://www.peekaboot.org/docs/api/#trace-list) the `bucket=all|errors|slow` filter
5. **Actuator not web-exposed**: all data read in-process from endpoint instances Peekaboot constructs itself, except `HealthEndpoint`, which is borrowed from the application; `PeekabootEndpointExposureOutcomeContributor` makes only that borrowed bean available without `management.endpoints.web.exposure` (see *In-Process Actuator Invocation*)
6. **Plain bounded maps for storage**: memory is bounded by the three bucket caps and the per-trace span and log caps, with no cache library. `max-logs-per-trace` may be 0: a trace then keeps no line, counts each as dropped, and an ERROR line still files it in Errors
7. **Shadow DOM**: the toolbar cannot interfere with the host application
8. **Lowest-priority defaults**: applications can always override Peekaboot settings
9. **One token sheet for four surfaces**: the dashboard, the toolbar, the trace overlay and the error page all load the same `tokens.css`, so every colour is defined once and no component hardcodes one. This keeps the CSS honest; it is not a supported theming API, and nothing outside the project is expected to replace the file. See [`peekaboot-frontend/README.md`](../peekaboot-frontend/README.md) for the fill/text pairing rule that constrains any change to it

## Auto-Configuration Ordering

`PeekabootTracingAutoConfiguration` runs after `PeekabootStorageAutoConfiguration`, whose
`StorageDirectory` its nested `TraceJournalConfiguration` needs (`@ConditionalOnBean`). The
journal's bean method asks that directory for a file and returns null on a second instance.

`DevToolbarAutoConfiguration` and `TraceCaptureAutoConfiguration` are ordered after both of
Boot's tracing bridge auto-configurations, OpenTelemetry's and Brave's, so the `Tracer` bean
exists whichever of them registers it.

The string-based `afterName` attribute is used where the referenced auto-configuration lives
in a module this one does not compile against at all: Boot's two tracing bridge modules
here, and `spring-boot-micrometer-metrics` for the `CompositeMeterRegistryAutoConfiguration`
edge in `InsightsAutoConfiguration`. Boot reads ordering edges from the class metadata without
loading the named classes, which is why `TracingInterceptorAutoConfiguration` can use a class
literal (`after = ObservationAutoConfiguration.class`) for a module the pom marks
`<optional>`. The class only has to exist at compile time.
