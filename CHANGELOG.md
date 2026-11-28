## 1.5.0

### Upgrading from 1.4.1

- **Gradle builds on Spring Boot 4.1 have to enforce Boot's BOM.** Peekaboot's poms now manage
  Spring Boot 4.2.0. A Gradle build on 4.1 that imports Boot's BOM with a plain `platform(...)`
  lets them lift Spring Boot to 4.2.0. Maven builds that take Boot's parent or BOM keep their
  version. To stay on 4.1, import Boot's BOM through the `io.spring.dependency-management` plugin
  or `enforcedPlatform(...)`.
- **Peekaboot turns Boot's active long-task timers on.** While Peekaboot is enabled in a servlet
  application it sets `management.metrics.observations.include-active-long-task-timer: true`.
  Spring Boot 4.2 defaults it to `false`, which left the "HTTP in flight" Insights panel empty.
  Every observation now registers an `<observation>.active` long-task timer, such as
  `http.server.requests.active`, in your `MeterRegistry`, and your metrics exporters send it.
  Boot 4.1 always registers them and ignores the property. To drop them, set the property to
  `false`; your value wins, and "HTTP in flight" stays empty.
- **An Insights override that rewrites a bundled series gets no OpenTelemetry meter.** A panel in
  your `peekaboot-insights.yml` that repeats a bundled id with a `title` replaces the bundled panel
  whole, so its series lack the `alternatives` the bundled ones now carry (see New). Under
  `management.observations.conventions=open-telemetry` such a series stays empty and logs a WARN
  like `Peekaboot insights: series 'heap.used': meter 'jvm.memory.used' did not resolve to any
  meter across 3 ticks`. An entry without a `title`, which only switches or moves a bundled panel,
  is unaffected. Copy the bundled series' `alternatives` from `peekaboot-insights-defaults.yml`
  into your override.
- **The Queries tab names each query's database by its JDBC URL.** A query's label and its
  `dbSystem` in `GET /peekaboot/api/traces/{traceId}` now hold the product the DataSource's JDBC
  URL names, such as `H2`, `POSTGRESQL` or `MYSQL`, as the Overview DataSource card and the
  health endpoint's `product` do. 1.4.1 served `postgresql`, `mysql`, `mariadb` and
  `microsoft.sql_server`, `other_sql` for H2 and every other database, and without
  `datasource-micrometer-opentelemetry` the pool name or the connection's catalog. A URL that
  names no product Peekaboot knows keeps the old value. While Peekaboot is enabled, the query
  spans your app exports also carry the product as `peekaboot.db.product`, a key your `jdbc.query`
  timer does not get. Nothing to do unless something you run parses `dbSystem`.
- **`/peekaboot/api/health` answers within `peekaboot.health.timeout`, default `10s`.** Every
  health check now runs at once. One still running at the deadline reads `DOWN` with
  `error: "Timed out after 10s"`, so the report answers `503`. The check is not interrupted:
  Peekaboot logs a WARN when it misses the deadline and another with its answer and duration when
  it ends. The Health section of the dashboard reads the same. A monitor with a shorter timeout
  than `10s` should get a lower `peekaboot.health.timeout`.
- **Health checks no longer run on the request thread.** 1.4.1 ran them one after another on the
  request thread. Now they run at the same time, each on its own virtual thread. A custom
  `HealthIndicator` no longer sees that thread's `SecurityContext`, MDC or request-scoped beans,
  and several database checks can hold pool connections at once. A check that relies on either
  throws, or reads differently, in Peekaboot's report than it does in `/actuator/health`. Inject
  what the check needs as beans, and size the pool for the checks that run together.
- **A health check that throws reads `DOWN` with an `error` detail.** 1.4.1 failed the whole
  report.
- **A replaced `HealthEndpoint` bean no longer answers for Peekaboot's health report.** In 1.4.1
  an application that declared its own `HealthEndpoint` bean saw that bean's answer on the
  dashboard and at `/peekaboot/api/health`. Both now list the checks Boot registered, answered
  within `peekaboot.health.timeout`. The replacement is read only where Boot's health registry
  or groups bean is missing, and then without a deadline. Declare a check of your own as a
  `HealthIndicator` bean to have it in the report.
- **Peekaboot's endpoints and schemas are gone from your OpenAPI document.** In an application with
  springdoc, `/v3/api-docs` no longer lists Peekaboot's own endpoints or the schemas only they use,
  while Peekaboot is enabled. A client generated from that document loses them. Your own
  controllers are unaffected.

### New

- **Every health component carries `durationMs`.** Each component of `/peekaboot/api/health`
  reports how long its check took, in milliseconds. A composite reports its slowest child's time.
- **Spring Boot 4.2.** Peekaboot runs on Spring Boot 4.1 and 4.2, where 1.4.1 supported 4.1 only.
- **OpenTelemetry semantic conventions.** With `management.observations.conventions=open-telemetry`
  on Boot 4.2, traces keep each HTTP request's route and status. HTTP throughput, HTTP latency,
  HTTP in flight, Heap memory and Non-heap memory in Insights fill from the OpenTelemetry meters,
  and so do the Process series of CPU usage, the CPU cores series of System load and, when
  switched on, Thread states. 1.4.1 left them empty under these conventions.
- **Renamed MDC keys keep logs linked.** On Boot 4.2 Peekaboot reads
  `management.tracing.mdc.trace-id-key` and `management.tracing.mdc.span-id-key`, so a log line
  stays linked to its trace when your app renames the keys. 1.4.1 read only `traceId` and
  `spanId` and listed such lines as untraced. On Boot 4.1 the keys stay `traceId` and `spanId`.
- **An Insights series can name `alternatives`.** In `peekaboot-insights.yml` a series may list
  meters to read when its own is absent, each a `meter` with optional `tags`, as in
  `alternatives: [{ meter: jvm.memory.used, tags: { jvm.memory.type: heap } }]`. Each tick the
  series reads the first of its `meter` and its alternatives that the registry has a meter for,
  with that candidate's tag keys. `stat`, `unit` and `subtract-meter` apply to whichever is read.

### Fixed

- **Screen reader names match what the control shows.** Speech-input users could not activate
  three controls by the text on them. The dev toolbar's button now reads its request summary
  before `Open request trace details`. A span button on the Logs tab is named by its span name,
  where 1.4.1 read out the span id. A copy button's label and value are separate words.

### Dependency upgrades

- **Spring Boot 4.2.0, from 4.1.1.** Peekaboot's poms manage it. The Spring Boot version you run
  still comes from your own parent or BOM, apart from the Gradle case under Upgrading from 1.4.1.

## 1.4.1 - 2026-11-26

### Fixed

- **Masking of DB2 URLs.** DB2 writes a JDBC URL's first property after a `:`, as in
  `jdbc:db2://db:50000/shop:password=…;`. On the Environment and Config tabs 1.4.0 showed that
  property's value in clear where its name held a secret word. It is now masked like the
  properties after `;`.
- **An Oracle user name no longer shows as the DataSource host.** ojdbc drops whitespace from a
  host, so in `jdbc:oracle:thin:dbadmin/pw@db admin:1521/svc` it connects to `dbadmin`. 1.4.0
  accepted that name because the user name spells it, and showed the user name as the host in the
  health report, on the DataSource card and in the startup banner. Such a URL now gets
  `connection: null`, and the card and banner hide its target, as for any host name the URL does
  not write out.

### Dependency upgrades

- **jdbc-url-parser 0.2.1, from 0.2.0.** A parse failure no longer carries a cause whose message
  repeats text from the URL. Peekaboot never logged that cause; your own code may have.

## 1.4.0 - 2026-11-24

### Behaviour changes

These need no action unless you relied on the old behaviour.

- **The Insights history is saved every minute again.** `peekaboot.insights.persistence.interval`
  defaults to `1m`, where 1.3.0 had `5m`. Each write appends only what changed, to
  `insights.journal` beside `insights.snapshot` in the storage directory. The snapshot is
  rewritten whole only from time to time and at shutdown. A crash or a kill loses up to one
  interval of history, and history saved by 1.3.0 is restored. Stop the application cleanly
  before going back to 1.3.0, which reads only the snapshot and would lose what the journal holds.
- **The DataSource card and the startup banner hide a target that could hold a credential.**
  They follow the rules of the health report's `connection` (see New). Where those refuse the
  JDBC URL, the Overview tab's DataSource card shows `-` for the database, `unknown` for the host
  and no connection params, and the banner prints `-` and an empty host list. That covers, for
  example, a URL with a `user=` or `password=` property. To see the target again, move the user
  and password into `spring.datasource.username` and `spring.datasource.password`.
- **The DataSource card and the banner read a URL as its driver does.** A host without a port
  shows the driver's default port, such as `5432` for PostgreSQL. Connection params split on the
  separator the driver uses, so `jdbc:postgresql://db/shop?a=1;b=2` lists one param `a` with the
  value `1;b=2`. HSQLDB names of `mem:` and single-segment server URLs show in lowercase. An
  Oracle descriptor's entries, such as `SERVICE_NAME`, are no longer listed among the params.
- **Connection params are masked by any secret word in their name.** On the DataSource card and
  in the banner, a param whose name holds `pass`, `pwd`, `secret`, `token`, `key` or `credential`
  anywhere reads `******`. That catches run-together names such as `sslpassword`, `PWD` and
  `keyVaultProviderClientKey`, which 1.3.0 showed in clear. Harmless params such as
  `keyStoreLocation` are masked too. A URL inside a property value on the Environment and Config
  tabs is judged the same way, so the `sslpassword` in a `spring.datasource.url` masks there as
  well. Property names keep whole-word matching, so `passwordless` and `server.ssl.key-store`
  stay readable.
- **Masking reads keys and values more closely.** Accents, control characters and invisible
  joining characters in a key are ignored, so `pássword`, `paſſword` and a `password` with a
  zero-width space inside it read `******` on every surface. A URL param value now ends at its
  own separator. A `;` property, as SQL Server and H2 write them, may hold `&`, a query value may
  hold `;`, and a SQL Server value in `{…}` is masked to its closing brace. 1.3.0 showed the part
  of such a value after a `&` or `;` in clear. A param after whitespace, as in
  `;\tpassword=`, is masked too.

### New

- **A health report for uptime monitors.** `GET /peekaboot/api/health` answers the application's
  health in the nesting `/actuator/health` uses. Each DataSource, Redis and mail component carries
  a `connection` with its hosts, ports and database, read from configuration, so it is there
  while the target is down. The report adds the current Insights problems, the Errors and Slow
  trace counts and up to ten recent failures, over level 0's window, which is 15 minutes with the
  default levels. The status code follows `/actuator/health`'s mapping, including
  `management.endpoint.health.status.http-mapping`, so `DOWN` and `OUT_OF_SERVICE` answer `503`.
  Set `peekaboot.health.fail-on-problems=true` to answer `503` also while a problem of severity
  `error` (`POOL_WAITING` or `SERVER_ERRORS`) is current. The endpoint is guarded like the rest of
  `/peekaboot/api`, and component details are masked.
- **The health report names a DataSource only where its URL holds no credential.** Only Hikari
  pools are described. A user and password before the first host, which Connector/J and ojdbc
  accept there, are left out. A DataSource gets `connection: null` where its JDBC URL could hide a
  credential: a property whose name contains `user`, `uid`, `pwd`, `pass`, `secret` or `token`, a
  `%` or `{`, a character outside ASCII, an `&` in a SQL Server URL, or a host, database or
  instance that is no plain name, or a URL its driver rejects, such as a PostgreSQL URL with a
  user before the host. Put the user and password in `spring.datasource.username` and
  `spring.datasource.password`. `/peekaboot/api/openapi.json` lists every rule.

### Fixed

- **DataSource credentials.** A user and password written before the host of a MySQL URL, as in
  `jdbc:mysql://user:pw@db/shop`, showed as part of the host on the Overview tab's DataSource card
  and in the startup banner. They are no longer shown there or among the connection params. A JDBC
  URL the parser could not read was logged whole, password included, in the WARN
  `Failed to extract metadata from DataSource '<name>': …`. That line now names the problem only.

### Dependency upgrades

- **jdbc-url-parser 0.2.0, from 0.1.3.** Peekaboot puts it on your classpath. It reads each URL
  as the product's driver does: a host without a port gets the driver's default, properties split
  on the driver's separator, a user and password before the host come back as `AUTHORITY`
  properties, and a URL the driver rejects throws. If your own code calls it, read its 0.2.0
  release notes before upgrading.

## 1.3.0 - 2026-11-16

### Needs action

These changes from 1.2.0 can break or alter a setup that changes nothing.

- **A local run answers only its own machine.** On a local run the dashboard and the API answer
  every request from elsewhere with a `403`. That covers a browser on another machine, a tunnel
  service, a reverse proxy, and a browser on the same machine that uses its network name or LAN
  address. A page served to such a client carries no dev toolbar, and the error page shows it the
  status and the path without the exception. Nothing changes with the guard on
  (`peekaboot.security.enabled=true`), on a deployment or in a test. To reach a local run from
  elsewhere, open an SSH tunnel to the machine's own `localhost`, or set
  `peekaboot.security.enabled=true` to put the login in front, or set
  `peekaboot.remote-access.enabled=true`. The last lets anyone who can reach the port read
  `/peekaboot/**` without signing in and logs a WARN at startup. With
  `server.forward-headers-strategy=native` another machine can pass the check, because the
  server replaces the client address with the one `X-Forwarded-For` claims. On a local run leave
  that strategy out, bind with `server.address=127.0.0.1`, or turn the guard on.
  `server.forward-headers-strategy=framework` is covered.
- **A generated password may sit in the trace files.** The `Peekaboot Security` block stays in
  the application log and no longer appears in the Logs tab, in `/peekaboot/api/logs` or in the
  trace files. If 1.2.0 ran with the guard on and `peekaboot.storage.enabled=true` and
  generated a password, `traces.journal.jsonl` and `traces.snapshot.jsonl` in the storage
  directory may still hold it in plain text. Delete the two files, which drops the stored
  traces, or delete the credentials file so that Peekaboot generates a new password on the next
  start.
- **`/actuator/info` is yours again.** Peekaboot no longer sets `management.info.env.enabled`,
  `management.info.java.enabled`, `management.info.os.enabled` or
  `management.info.process.enabled`. Where Peekaboot is on, your `/actuator/info` is back to
  what Spring Boot serves, so `java`, `os`, `process` and your `info.*` properties drop out of
  it. Set the four properties yourself if you relied on that content. The Overview is
  unchanged.
- **DataSources stay unwrapped while Peekaboot is off.** `jdbc.datasource-proxy.enabled` now
  defaults to `false` while `peekaboot.enabled` is `false`, which is the default on a
  deployment and in a test. If you use datasource-micrometer there for your own query spans or
  metrics, set `jdbc.datasource-proxy.enabled=true`. A value you set always wins.
- **A second instance on one storage directory keeps its history in memory.** An instance that
  starts on a storage directory a running instance holds no longer reads or writes there. It
  logs one WARN, `Peekaboot: another running instance writes to <dir>; this instance keeps its
  history in memory. Give each instance its own peekaboot.storage.dir.` It starts without
  history, does not take over when the first instance stops, and where the guard generates the
  password it generates one of its own. The default directory is per application, so this
  reaches two instances of one application on one machine or on a shared volume. Give each its
  own `peekaboot.storage.dir`. The directory now holds a `.lock` file and is created at startup.
- **A custom `TraceStore` has to be restorable while trace storage is on.** The four replay
  methods (`beginRun`, `restoreRemnant`, `holdAll`, `restoreBucket`) moved from `TraceStore` to
  `RestorableTraceStore`. Trace storage is on by default on a local run. Add
  `implements RestorableTraceStore` and declare the bean method's return type as
  `RestorableTraceStore` or as your class. Otherwise the context can stop with `No qualifying
  bean of type 'org.peekaboot.backend.tracing.store.RestorableTraceStore'` on
  `peekabootTraceJournal`. A store that implements `TraceStore` alone works with
  `peekaboot.storage.traces.enabled=false`.
- **A credentials file others can read is not used.** Peekaboot logs `The Peekaboot
  credentials file <file> is accessible to its group or to others; generating a new password`
  at WARN and generates a new password. This reaches a file that was copied, written by hand or
  mounted from a volume that sets its own modes, and a file whose hash was written with another
  iteration count. `chmod 600` the file before upgrading to keep the password. Where the volume
  fixes the modes, the password would change on every restart, so set
  `peekaboot.security.password` there. A file system without POSIX permissions is not checked.
- **Local-run detection is stricter.** Some launches that 1.2.0 took for a local run now count
  as a deployment, for example a launch on Jetty's `WebAppClassLoader` and a container whose
  environment or mounts show it. On such a launch Peekaboot, the dev toolbar, storage and the
  error page are off by default, and once Peekaboot is on the guard asks for a login. Set
  `peekaboot.enabled=true` and the other switches yourself there.

### Behaviour changes

These need no action unless you relied on the old behaviour.

- **The Insights history is saved every five minutes.** `peekaboot.insights.persistence.interval`
  defaults to `5m`, where it was `1m`. A crash or a kill loses up to five minutes of history. Set
  `1m` for the old bound.
- **The request path of a trace is masked.** `summary.request.path` and
  `data.httpExchange.request.path` are masked by value shape, for stored traces too. The `path`
  filter of `GET /peekaboot/api/traces` matches the masked path, so a prefix that runs into a
  masked part has to spell it `******`.
- **Masking covers more.** Values under keys such as `app.pass`, `db.pw`, `sentry.dsn`,
  `encrypt.key` and `jwt` now read `******` on every surface, and so does a harmless
  `app.pass-through.enabled`. `passwordless` and `dsname` stay readable. A quoted
  `name="value"` option with a sensitive name, such as `password="…"` in `sasl.jaas.config`, and
  URL credentials whose password holds `:`, `/` or `@` are masked too. The error page masks its
  message and stack trace. To read a masked value in the Environment or Config tab, set
  `peekaboot.unmasking.enabled=true` and use "Show secrets".
- **Form parameters are listed only when your code read them.** The Request tab lists them when
  the application, or a filter after Peekaboot's capture filter, read them through the servlet
  parameter API. A form post whose body your code reads as a stream shows none. A key sent in
  both the query string and the form lists its query values only.
- **Long captured values are cut.** Each cut value ends in `... N characters omitted`. The
  limits are fixed: 4,096 characters for a header or parameter name or value, 16,000 for a span
  tag value, an exception message, a log message, a statement's bind values or a SQL statement.
- **The dashboard cannot be framed.** `/peekaboot/ui/**` is served with a
  `Content-Security-Policy` that includes `frame-ancestors 'none'`, plus `X-Frame-Options: DENY`
  and `X-Content-Type-Options: nosniff`. A header your application has already set is kept.
- **Insights medians and percentiles change at coarser levels.** With the default levels the
  values change in the `1h` windows and in every summary above `10s`. That moves `median` and
  `p95` in `/peekaboot/api/insights/data`, and the `SLOW_HTTP`, `CPU_HIGH` and `GC_OVERHEAD`
  problems read from them. `min`, `avg` and `max` are unchanged. A history restored from 1.2.0
  mixes old and new windows until the old ones age out.
- **Fewer spans are merged.** Only a datasource span recorded twice is merged into its parent,
  and only when both failed or both succeeded. Other nested spans with the same name and tags
  now show separately, so a trace can reach `peekaboot.tracing.max-spans-per-trace` sooner.
- **DataSource metadata is read after startup.** The startup summary waits up to 2 s for it and
  prints `no connection yet` for a DataSource that has not answered. The dashboard shows the
  metadata once a connection succeeds. A pool that connects lazily is still opened by Peekaboot,
  at application ready.
- **More startup lines among the untraced logs.** Lines that beans log while the application
  context is being built now appear among the untraced lines of the Logs tab and
  `/peekaboot/api/logs`, and count against `peekaboot.tracing.max-untraced-logs`.

### New

- **The Traces tab loads older traces on request.** It says how many rows it shows and has a
  "Load more" button that lists 50 more, up to the most one listing answer serves.
- **`GET /peekaboot/api/metrics` takes `name`.** It keeps the meters whose name starts with the
  value, as written. Without it, or blank, every meter is listed as before.
- **Scheduled tasks on a custom trigger are listed.** They showed nowhere before. They now
  appear in the Scheduled Tasks tab with the trigger's own description, and the response carries
  a `custom` list and a count.

### Fixed

- **Guard.** A path prefix, a case-insensitive matcher or a second `DispatcherServlet` could
  serve the API and the UI outside `/peekaboot/**`, past the guard. A flood of wrong passwords
  no longer costs one key derivation each on the request threads.
- **Dev toolbar.** A handler that sets a `WriteListener` no longer hangs until the async timeout,
  and `reset()` on the response works. A `206` answer to a `Range` request gets no toolbar. A
  page in ISO-8859-1 no longer shows question marks. An error in the toolbar no longer rejects
  the page's own `fetch`.
- **Startup.** A servlet application without `spring-webmvc` failed to start. A malformed
  unicode escape in `git.properties` failed the read. A missing
  `peekaboot.insights.config-location` now stops startup with a message that names the
  property. `peekaboot.tracing.max-traces` and `max-spans-per-trace` below 1, and
  `max-error-traces` and `max-slow-traces` below 0, are refused at binding.
- **Capture.** An application listener for every event no longer hears each captured log line,
  span and request. A failure while capturing a request no longer replaces the handler's own
  exception. A request with a `traceparent` header to an excluded path no longer leaves a trace.
  One span that fails to store no longer drops the rest of its batch. A trace nested past about
  250 spans is served, where it failed to serialise.
- **Trace storage.** A journal line the store refuses is skipped on replay. Two processes that
  replace one file no longer move each other's unfinished write into place.
- **Insights.** A meter whose read throws costs only its own series. A tag combination that
  appears or leaves no longer reads as a reset or a spike. A roll-up covers only the ticks
  before its boundary. A dropped event stream ends on Tomcat too. Two series that spell one id
  are refused at startup.
- **Lifecycle.** A context that closes before it was ready records no stop. A start left by a
  killed process ends without a stop. A file with a byte that is not UTF-8 is read line by line
  and no longer erased by the next event, and a file past 4 MiB is read from its newest end.
- **Dashboard refresh.** A refresh keeps focus, open groups, a text selection and open connection
  parameters. A tab that fails shows its error in its own panel. A failed `/api/features`
  request is retried on every refresh.
- **Dashboard values.** `999.6 ms` no longer reads `1000ms`, and durations reach hours and days.
  Decimals follow the dashboard language. A text health detail named `total`, `free` or
  `threshold` is shown as sent. A repeatable Flyway migration is labelled `Repeatable`.
- **Dashboard keyboard and themes.** The Insights chart selection shows in the dark theme, and
  restart markers can be reached with the arrow keys. Arrow, Home and End moves between tabs no
  longer add Back stops. Reopening a trace keeps the URL hash.
- **API.** A route that does not start with `/` is a `400` on `GET /peekaboot/api/traces` and
  `/errors`, not an empty list. An ill-formed `locale` is a `400`. A `limit` above 100 with
  `view=full` is a `400`. An infinite meter or insights value is served as `null`, not as the
  string `"Infinity"`. A scheduled job matches `rootOperation` on its own target, not on any
  suffix of a dotted value. A scheduled task without a target sorts. A span without tags is no
  HTTP request.
- **Jars.** Every jar carries `LICENSE` and `NOTICE` under `META-INF/`.

### Performance

- **Trace listings and routes are cheaper to build.** `GET /peekaboot/api/routes` and the trace
  summary rows do less work per trace.
- **A log search reads the newest 10,000 lines.** `next` says where the search stopped, so a
  page can come short.
