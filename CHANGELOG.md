## 1.3.0 - 2026-11-16

### Upgrading from 1.2.0

These changes reach you without any action.

- **Local run answers only its own machine.** On a local run the dashboard and the API answer
  every request from elsewhere with a `403`. A page served to such a client carries no dev
  toolbar, and the error page shows it the status and the path without the exception. A request
  passes when its client address is a loopback address, its `Host` header names `localhost`, a
  name ending in `.localhost` or a loopback address, and no proxy and no other site relayed it.
  So a browser on another machine gets the `403`, and so does a tunnel service, a reverse
  proxy, and a browser on the same machine that addresses it by its network name or LAN
  address. Nothing changes with the guard on (`peekaboot.security.enabled=true`), on a
  deployment or in a test. To reach a local run from elsewhere, open an SSH tunnel to the
  machine's own `localhost`, or set `peekaboot.security.enabled=true` to put the login in
  front, or set `peekaboot.remote-access.enabled=true`. The last lets anyone who can reach the
  port read `/peekaboot/**` without signing in and logs a WARN at startup. With
  `server.forward-headers-strategy=native` the rule can be passed from another machine, because
  the server replaces the client address with the one `X-Forwarded-For` claims before Peekaboot
  sees the request. On a local run leave that strategy out, bind with
  `server.address=127.0.0.1`, or turn the guard on. `server.forward-headers-strategy=framework`
  is covered.
- **A generated password may sit in the trace files.** The `Peekaboot Security` block stays in
  the application log and no longer appears in the Logs tab, in `/peekaboot/api/logs` or in the
  trace files. If 1.2.0 ran with the guard on and `peekaboot.storage.enabled=true` and
  generated a password, `traces.journal.jsonl` and `traces.snapshot.jsonl` in the storage
  directory may still hold that password in plain text. Delete the two files, which drops the
  stored traces, or delete the credentials file so that Peekaboot generates a new password on
  the next start.
- **`/actuator/info` is yours again.** Peekaboot no longer sets `management.info.env.enabled`,
  `management.info.java.enabled`, `management.info.os.enabled` or
  `management.info.process.enabled`. Where Peekaboot is on, your `/actuator/info` is back to
  what Spring Boot serves, so `java`, `os`, `process` and your `info.*` properties drop out of
  it. Set the four properties yourself if you relied on that content. The Overview is
  unchanged and shows Java, OS and memory whatever those properties say.
- **DataSources stay unwrapped while Peekaboot is off.** `jdbc.datasource-proxy.enabled` now
  defaults to `false` while `peekaboot.enabled` is `false`, which is the default on a
  deployment and in a test. If you use datasource-micrometer there for your own query spans or
  metrics, set `jdbc.datasource-proxy.enabled=true`. A value you set always wins.
- **A second instance on one storage directory keeps its history in memory.** An instance that
  starts on a storage directory a running instance holds no longer reads or writes there. It
  logs one WARN, `Peekaboot: another running instance writes to <dir>; this instance keeps its
  history in memory. Give each instance its own peekaboot.storage.dir.` It starts without
  history, keeps Insights, the run history, traces and the error digest in memory, and does
  not take over when the first instance stops. Where the guard generates the password, it
  generates one of its own. The default directory is per application, so this reaches two
  instances of one application on one machine or on a shared volume. Give each its own
  `peekaboot.storage.dir`. The directory now holds a `.lock` file and is created at startup,
  where it used to appear with the first write.
- **A custom `TraceStore` has to be restorable while trace storage is on.** The four replay
  methods (`beginRun`, `restoreRemnant`, `holdAll`, `restoreBucket`) moved from `TraceStore` to
  `RestorableTraceStore`, and the trace journal asks for that type. Trace storage is on by
  default on a local run (`peekaboot.storage.enabled` on and `peekaboot.storage.traces.enabled`
  not `false`). A store written against 1.2.0 already has the four methods. Add `implements
  RestorableTraceStore` and declare the bean method's return type as `RestorableTraceStore` or
  as your class. Otherwise the context can stop with `No qualifying bean of type
  'org.peekaboot.backend.tracing.store.RestorableTraceStore'` on `peekabootTraceJournal`. A
  store that implements `TraceStore` alone works with `peekaboot.storage.traces.enabled=false`.
- **The Insights history is saved every five minutes.** `peekaboot.insights.persistence.interval`
  now defaults to `5m`, where it was `1m`. A crash or a kill loses up to five minutes of
  Insights history. A normal shutdown loses nothing. Set `1m` for the old bound.
- **The request path of a trace is masked.** `summary.request.path` and
  `data.httpExchange.request.path` are masked by value shape, for traces stored before the
  upgrade too. The `path` filter of `GET /peekaboot/api/traces` matches the masked path, so a
  prefix that runs into a masked part has to spell it `******`.
- **Masking covers more.** A key is masked when it holds `pass`, `pw`, `passcode`,
  `account-key`, `master-key` or `dsn` as a whole word, or when the whole key is `encrypt.key`
  or `jwt`. Values under `app.pass`, `db.pw` or `sentry.dsn` now read `******` on every
  surface, and so does a harmless `app.pass-through.enabled`. `passwordless` and `dsname` stay
  readable. A quoted `name="value"` option with a sensitive name is masked inside any value,
  such as `password="…"` in `sasl.jaas.config`. URL credentials are masked when the password
  holds `:`, `/` or `@`. The error page masks its message and stack trace by value shape. To
  read a masked value in the Environment or Config tab, set `peekaboot.unmasking.enabled=true`
  and use "Show secrets".
- **Form parameters are listed only when your code read them.** The Request tab lists form
  parameters when the application, or a filter that runs after Peekaboot's capture filter, read
  them through the servlet parameter API. Peekaboot no longer parses a form body itself. A
  form post whose body your code reads as a stream shows no form parameters. Query parameters
  are read from the query string, and a key sent in both lists its query values only.
- **Long captured values are cut.** A header or parameter name or value over 4,096 characters
  is cut at its last whitespace. A span tag value, an exception message or a log message over
  16,000 characters is cut too. Each ends in `... N characters omitted`. One statement records
  at most 16,000 characters of bind values, and a SQL statement over 16,000 characters is
  shown cut. The limits are fixed.
- **The dashboard cannot be framed.** `/peekaboot/ui/**` is served with
  `Content-Security-Policy: default-src 'self'; object-src 'none'; base-uri 'self';
  frame-ancestors 'none'; form-action 'self'`, `X-Frame-Options: DENY` and
  `X-Content-Type-Options: nosniff`. A page that showed the dashboard in a frame no longer
  can. A header your application has already set on the response is kept.
- **A credentials file others can read is not used.** Peekaboot logs `The Peekaboot
  credentials file <file> is accessible to its group or to others; generating a new password`
  at WARN and generates a new password. Peekaboot writes the file owner-only itself, so this
  reaches a file that was copied, written by hand or mounted from a volume that sets its own
  modes. `chmod 600` the file before upgrading to keep the password. Where the volume fixes
  the modes, the password would change on every restart, so set `peekaboot.security.password`
  there. A file system without POSIX permissions is not checked. A file whose hash was written
  with another iteration count is refused the same way.
- **Local-run detection is stricter.** A non-blank `container` environment variable now counts
  as a container, and so does a `/proc/1/mountinfo` that mounts `/etc/hosts`, `/etc/hostname`
  or `/etc/resolv.conf`. The main thread's class loader has to be the JVM's system class loader
  itself, so a launch on another loader whose class name contains `AppClassLoader`, such as
  Jetty's `WebAppClassLoader`, is no longer taken for a local run. On such a launch Peekaboot,
  the dev toolbar, storage and the error page are off by default. Set `peekaboot.enabled=true`
  and the other switches yourself there. The launch counts as a deployment, so once Peekaboot
  is on the guard asks for a login.
- **Insights medians and percentiles change at coarser levels.** A coarser level now takes its
  median and percentiles from the finer windows' own median and percentiles, where it took
  them from the windows' averages. With the default levels the values change in the `1h`
  windows and in every summary above `10s`. That moves `median` and `p95` in the series
  summaries of `/peekaboot/api/insights/data`, and the `SLOW_HTTP`, `CPU_HIGH` and
  `GC_OVERHEAD` problems read from them. `min`, `avg` and `max` are unchanged. A history
  restored from 1.2.0 mixes old and new windows until the old ones age out.
- **Fewer spans are merged.** Only a datasource span recorded twice is merged into its parent,
  and only when both failed or both succeeded. Other nested spans with the same name and tags
  now show as separate spans. A trace with such spans counts more spans against
  `peekaboot.tracing.max-spans-per-trace` and can reach the cap sooner.
- **DataSource metadata is read after startup.** The startup summary waits up to 2 s for it and
  prints `no connection yet` for a DataSource that has not answered. The dashboard shows the
  metadata once a connection succeeds. A failed read is repeated at most every 30 s, and only
  while the dashboard is read. A pool that connects lazily is still opened by Peekaboot, at
  application ready.
- **More startup lines among the untraced logs.** Lines that beans log while the application
  context is still being built are now captured, from the moment Peekaboot's log capture
  starts. They appear among the untraced lines of the Logs tab and `/peekaboot/api/logs` and
  count against `peekaboot.tracing.max-untraced-logs`.


### New

- **The Traces tab loads older traces on request.** The tab listed the 50 newest rows and did
  not say so. It now says how many rows it shows and has a "Load more" button that lists 50 more,
  up to the most one listing answer serves.
- **`GET /peekaboot/api/metrics` takes `name`.** The parameter keeps the meters whose name starts
  with the value, as written. Without it, or blank, every meter is listed as before.
- **Scheduled tasks on a custom trigger are listed.** A task whose trigger is neither a cron
  expression nor a fixed interval used to be missing from the Scheduled Tasks tab. It now shows
  with the trigger's own description, and the response carries a `custom` list and a count.

### Fixed

- **Guard.** A path prefix, a case-insensitive matcher or a second `DispatcherServlet` in the
  host could serve the API and the UI outside `/peekaboot/**`, past the guard. The guard now
  covers Peekaboot's handlers wherever they are mapped. `peekaboot.security.deployment-detected`
  can no longer hide a deployment from the security check. Password checks that run at once are
  bounded, so a flood of wrong passwords no longer costs one key derivation each on the request
  threads. A sign-in over the bound is refused and logged once. The username of a rejected
  sign-in is logged without control characters.
- **Dev toolbar.** A handler that sets a `WriteListener` no longer hangs until the async timeout,
  and `reset()` on the response works. A `206` answer to a `Range` request gets no toolbar. The
  toolbar is written in ASCII, so a page in ISO-8859-1 no longer shows question marks. An error
  in the toolbar's own `fetch` wrapper can no longer reject the page's `fetch`. More client
  disconnects are recognised and not logged as failures.
- **Startup.** A servlet application without `spring-webmvc` failed to start, and now Peekaboot
  backs off. A malformed unicode escape in `git.properties` no longer fails the read. A missing
  `peekaboot.insights.config-location` stops startup with a message that names the property,
  where the bundled panels were served silently. `peekaboot.tracing.max-traces` and
  `max-spans-per-trace` below 1, and `max-error-traces` and `max-slow-traces` below 0, are
  refused at binding. Every `@ConfigurationProperties` bind on a host that carries the starter
  no longer walks its nested beans to their leaves.
- **Capture.** An application listener for every event no longer hears each captured log line,
  span and request, and the listener instrumentation no longer wraps those calls in spans. A
  failure while capturing a request no longer replaces the handler's own exception. A request
  that carries a `traceparent` header to an excluded path leaves no trace. One span that fails
  to store no longer drops the rest of its batch. A span that names itself as its parent is
  refused. A trace nested past about 250 spans is served, where it failed to serialise.
- **Trace storage.** A journal line the store refuses is skipped on replay. The journal writer
  runs on a platform thread. It warns once when it stops on a `VirtualMachineError`, where it
  stopped without a word. Each atomic write uses its own temporary file, so two processes that
  replace one file no longer move each other's unfinished write into place. A write that is
  interrupted at shutdown ends.
- **Insights.** A meter whose read throws costs only its own series, which records a gap and
  warns once. Rates are taken from each meter's own previous reading, so a tag combination that
  appears or leaves no longer reads as a reset or a spike. A roll-up covers only the ticks
  before its boundary. A thread that an error ended is started again. A dropped event stream
  ends on Tomcat too, and no sender thread is left behind at shutdown. Two series that spell
  one id are refused at startup. A write outage is reported once per outage and again after the
  next one. The loaded history is released after it is handed over.
- **Lifecycle.** A context that closes before it was ready records no stop. Only the run of this
  process counts as running, so a start left by a killed process ends without a stop. A start is
  kept however long the history takes to load. The history loads in time order. A file with a
  byte that is not UTF-8 is read line by line and no longer erased by the next event, and a
  file past 4 MiB is read from its newest end.
- **Dashboard refresh.** A refresh keeps focus, open groups, a text selection and open connection
  parameters, because rows and cards update by key and not by rebuild. Only the visible tab is
  rendered, and a tab that fails shows its error in its own panel, not in the page banner. A
  request that a newer one overtook is dropped. A failed `/api/features` request is retried on
  every refresh. The insights stream closes while the page is hidden.
- **Dashboard values.** Durations and sizes are rounded into the next unit, so `999.6 ms` no
  longer reads `1000ms`, and durations reach hours and days. Decimals follow the dashboard
  language, also in the dev toolbar and the trace overlay. A text health detail named `total`,
  `free` or `threshold` is shown as sent. A repeatable Flyway migration is labelled
  `Repeatable`.
- **Dashboard keyboard and themes.** The Insights chart selection shows in the dark theme, and
  restart markers can be reached with the arrow keys. Arrow, Home and End moves between tabs no
  longer add Back stops. Reopening a trace keeps the URL hash. Overlay gaps for screen readers
  are closed.
- **API.** A route that does not start with `/` is answered with `400` on `GET
  /peekaboot/api/traces` and `/errors`, where it matched nothing and gave an empty list. An
  ill-formed `locale` is a `400`. A `limit` above 100 with `view=full` is a `400`, where one
  request could map the whole store. An infinite meter or insights value is served as `null`,
  not as the string `"Infinity"`. A scheduled job matches `rootOperation` on its own target,
  not on any suffix of a dotted value. A scheduled task without a target sorts. A span without
  tags is no HTTP request.
- **Jars.** Every jar carries `LICENSE` and `NOTICE` under `META-INF/`.

### Performance

- **Trace listings and routes map less.** A summary row no longer masks every tag or lays out
  every statement it does not serve. `GET /peekaboot/api/routes` reads the listing facts and maps
  a trace only for its issue types. A verdict filter under `view=full` judges async rows on a
  summary. A summary walks its span tree once, and the walk no longer costs a frame per level.
- **A log search reads the newest 10,000 lines.** A rare `q` used to map and mask every stored
  line for one page. `next` says where the search stopped, so a page can come short.
- **Trace storage does less per span.** A trace that is not in Slow no longer takes Slow's
  monitor per span. A digested trace is looked up once. A start after a clean stop reads the
  journal files once and skips a fold with nothing to fold.
