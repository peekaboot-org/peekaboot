# Building Peekaboot

Maven is the system of record. One reactor. Static-analysis tools, dependency and output
checks and a coverage floor run as gates across the build lifecycle (see
[Quality gates](#quality-gates)). No codegen beyond annotation processing. One module,
`peekaboot-frontend`, downloads a pinned Node to lint its JS, CSS and HTML; nothing else
in the build needs it and nothing uses the machine's own Node.

A parallel Gradle build covers the same modules, tests and gates (see
[the Gradle build](#the-parallel-gradle-build) below). CI runs Maven, and Gradle only for
Dependabot branches, so every Maven statement in this document is authoritative and the
Gradle build must be kept in lockstep.

## Prerequisites

| | |
| --- | --- |
| JDK | **25**, the root pom's `peekaboot.java.version`, which both builds compile against. No toolchains, no fallback |
| Maven / Gradle | The checked-in wrappers. Each fetches its own build tool at the version and SHA-256 pinned in `.mvn/wrapper/maven-wrapper.properties` and `gradle/wrapper/gradle-wrapper.properties`. A local Maven 3.9+ works the same |
| Docker | Needed by `./mvnw verify`: three testing-app ITs start their engine through Testcontainers (see its [README](peekaboot-testing-app/README.md#the-automated-ui-suite-srctest)). Also for running the sample app and for `ScreenshotCapture` |
| Network | First run only: the wrappers fetch their build tool, the frontend gate fetches Node and its npm packages, Playwright fetches Chromium into `~/.cache/ms-playwright`, and Testcontainers pulls the MySQL, Redis and go-httpbin images |

## Commands

```bash
./mvnw clean verify    # compile + all tests + every gate             <- the real build
./mvnw clean install   # the same, plus install into ~/.m2
./mvnw test            # the fast gate: the dependency/output checks + Error Prone
                       # + unit tests only; integration tests (*IT) don't run
                       # before `verify`
./mvnw spotless:apply  # format (local builds already do this for you)

./mvnw -pl <module> test -Dtest=<Class>             # one unit-test class; never add -am
./mvnw -pl <module> verify -Dit.test=<Class>        # one *IT class (runs this module's
                                                    # gates too; that's inherent to verify)
./mvnw -pl <module> -am verify -Dit.test=<Class> \
    -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false         # the same against uncommitted sibling-module
                                                    # changes: both skip flags, or failsafe fails the
                                                    # first upstream module with "No tests matching pattern"
./mvnw -pl peekaboot-testing-app spring-boot:run    # sample app on :8093; needs Docker
                                                    # and an `./mvnw install` beforehand
```

### What each command actually checks

Tests are split by lifecycle. Unit tests live in `*Test` classes and run at `test` under
surefire. Anything that boots a real application (every `@SpringBootTest`, the whole
Playwright suite) lives in `*IT` classes and runs at `integration-test` under failsafe.
That is what makes `./mvnw test` the fast gate: the dependency/output checks, Error Prone
and every unit test, nothing else. `./mvnw verify` adds the integration tests and the
remaining gates. Per module, `verify` runs:

```
unit tests → package → sources jar → javadoc jar → integration tests (*IT) → spotless:check → spotbugs:check → checkstyle:check → pmd:check
```

`peekaboot-testing-app` runs its `*IT` classes concurrently inside one JVM: 2 worker
threads (`-Dpeekaboot.it.threads=N`; `1` serializes when diagnosing a flaky test), each
owning its own browser, all sharing one Spring context cache and therefore one running
app per context configuration. The concurrency is deliberate beyond speed: concurrent test
classes hammer peekaboot the way a real concurrent host application does, so a race in
peekaboot itself shows up here first. No class holds a JUnit `@ResourceLock`: every one of
them pins its own trace id instead of clearing state the others are using.
`-Dpeekaboot.it.forks=N` still exists on top (forks × threads both apply) but defaults to 1.
The coverage gate sees the same `jacoco.exec` data it would from a serial run.

`-Dpeekaboot.it.browser=<chromium|firefox|webkit>` picks the Playwright engine that suite
drives, and defaults to `chromium`. An unknown value fails the run rather than falling back.
Firefox and WebKit hold back the tests tagged `chromium-only`, whose subject is Chromium's
own behaviour; the per-engine profiles in the testing-app pom do that. Nightly coverage of
all three is [`cross-browser.yml`](#cross-browseryml).

`peekaboot-coverage` runs last and adds the coverage gate over the whole reactor:

```
jacoco:merge -> enforcer (coverage data present?) -> jacoco:report-aggregate -> jacoco:check
```

Gates run *after* the tests, so a failing unit test hides every gate failure behind it. A
failing `*IT` differs: the test itself ran at `integration-test`, and `failsafe:verify`
only reports the result, at `verify`. Where that report lands relative to the verify-bound
gates is POM declaration order, which is what Maven follows within a phase. Modules
inheriting the gates from the parent get them first, so there the gates still run.
`peekaboot-testing-app` declares them itself in the opposite order, so
`failsafe:verify` comes first and a broken `*IT` stops the build ahead of
pmd → checkstyle → spotbugs → spotless. That module also adds `spring-boot:repackage`, so
it is the only one producing an executable jar.

The Playwright suite is the bulk of a cold `./mvnw clean verify`. `./mvnw test` alone is a
fraction of it.

## The reactor

| Module | Artifact | Published | Contains |
| --- | --- | --- | --- |
| `peekaboot-parent` | pom | yes | Shared build config, dependency management (the `spring-boot-dependencies` BOM) |
| `peekaboot-test-support` | jar | **no** (`skipPublishing`, see [Releasing](#releasing)) | Shared test helpers, consumed at test scope by the other modules' suites. See its [README](peekaboot-test-support/README.md) |
| `peekaboot-backend` | jar | yes | Controllers, services, trace store, lifecycle listeners, every `@ConfigurationProperties` class and therefore the configuration metadata. Its web/servlet/logback/Hikari/OTel deps are `<optional>`: the host app supplies them, and the auto-configuration conditions guard their use |
| `peekaboot-frontend` | jar | yes | `src/main/resources/META-INF/peekaboot/ui/**` only, outside every default static location, so a consumer with Peekaboot off serves none of it. Plain ES modules and CSS copied as-is: no build step, no test sources. Empty `-javadoc` jar (below) |
| `peekaboot-spring-boot-autoconfigure` | jar | yes | Auto-configuration classes, `AutoConfiguration.imports` and `spring.factories` |
| `peekaboot-spring-boot-starter` | jar | yes | Dependency aggregator, no sources. Maven logs `JAR will be empty`, which is correct. Empty `-sources` and `-javadoc` jars (below) |
| `peekaboot-testing-app` | jar (boot) | **no** (`maven.deploy.skip`) | Sample app + the Playwright UI suite. See its [README](peekaboot-testing-app/README.md) |
| `peekaboot-coverage` | pom | **no** (`skipPublishing`) | No sources. Merges every module's coverage data, renders the aggregate report, enforces the floor. Builds last |

Maven Central requires a `-sources` and a `-javadoc` jar for every jar component, and two
published modules have no Java sources. `maven-source-plugin` would build nothing for the
starter, and `javadoc:jar` builds nothing for the starter or the frontend. So the starter
sets `maven.source.forceCreation`, and both modules package their empty `target/apidocs`
as the `-javadoc` jar through an extra `maven-jar-plugin` execution. Empty is intended.

`peekaboot-testing-app` deliberately parents to `spring-boot-starter-parent`. The split
proves two things: the sample app builds on Boot's own plugin defaults (`-parameters`,
`@..@` resource filtering), and it leans on nothing in `peekaboot-parent`'s build config.
It proves nothing about consuming the published artifact. Inside the reactor the starter
resolves from the reactor under either parent, and both parents manage dependencies
through the same `spring-boot-dependencies` BOM, so dependency resolution is identical. A
real consume-as-a-user check would build outside the reactor against an installed jar,
and nothing here does that. The cost of the split is duplication: its POM re-declares the
verify-bound static-analysis gates, the JaCoCo and Mockito agent wiring, the
`spotless-apply-local` profile and the Error Prone compiler config by hand, pins the
compiler, dependency, surefire and failsafe plugins at the parent's versions, and picks
up Spring Boot's plugin versions for everything else. `BuildVersionLockstepTest` compares
the copies its Javadoc lists with the root pom; every other change to the parent's build
config has to be mirrored there by hand. The one deliberate
exception is the dependency check: the sample app is the module that violates it (see
[the dependency check](#the-dependency-check)), and gating an unpublished sample on a
third-party version clash would buy nothing but two permanent exclusions.

## The parallel Gradle build

`settings.gradle.kts` includes the modules the root pom lists. `./gradlew build` is the
`./mvnw clean verify` equivalent. It runs unit tests (`test`, `*Test` and `*Tests`) and
integration tests (`integrationTest`, `*IT`, concurrent classes exactly like failsafe, with
the testing-app pom's `peekaboot.it.threads` unless `-Ppeekaboot.it.threads=N` overrides it). It runs the static-analysis gates at the
same tool versions, reading the same `config/` files, plus the reactor-wide coverage gate
(`:peekaboot-coverage:coverageGate`, the same 90%/75% floors on merged execution data).
`./gradlew test` is the fast gate, `./gradlew assemble` just builds the jars.

`buildSrc/src/main/kotlin/peekaboot.java-conventions.gradle.kts` plays the role of
`peekaboot-parent`: shared compiler, gate, JaCoCo and test-split config. Each module's
`build.gradle.kts` declares only its dependencies; `peekaboot-testing-app` adds the Spring
Boot and git-properties plugins. Gradle has no counterpart to a Maven parent, so what the
Maven module's `spring-boot-starter-parent` proves (see [The reactor](#the-reactor)) has
no Gradle equivalent; the module simply shares the conventions.

### Lockstep

The Gradle build copies nothing the poms declare. `settings.gradle.kts` reads the module
list, the module `<version>` and every `<properties>` entry out of the root pom and each
module's pom, a module's own properties over the root pom's. Scripts read one with
`pomProperty("...")` from `buildSrc/src/main/kotlin/PomProperties.kt`; `buildSrc`, a build of
its own, reads `spring-boot.version` for the Boot plugin directly. So the Spring Boot line,
datasource-micrometer, JaCoCo, PMD, the compiler release level, the coverage floors, the Node
pin, Playwright, the IT defaults and the build instant each live in one pom. Mockito's agent
jar takes its version from the Boot BOM, as Maven does. `maven-release-plugin` rewrites the
poms alone and Dependabot bumps a pom property alone, so a copy on the Gradle side would go
stale behind a green build, because CI builds Gradle only on Dependabot branches.
`BuildVersionLockstepTest` fails if `gradle.properties` holds anything but Gradle's own
settings, or if a Gradle script pins a build instant of its own.

A value with no pom property behind it is written on both sides and has to change on both:
Error Prone, palantir, Checkstyle, SpotBugs and Spotless, all spelled inside plugin
configuration, the root pom's slf4j-api override (a constraint in the convention plugin),
jdbc-url-parser, cron-utils, the testing-app's springdoc, datasource-proxy and axe-core, and
the frontend gate's `npm ci`. `BuildVersionLockstepTest` compares every pair. SpotBugs and
Spotless compare through a mapping, because one side names a plugin whose version is not the
tool's. The
frontend's *lint tool* versions are not on that list. They live once in
`peekaboot-frontend/package.json`, which both builds install from, so there is nothing to
drift.

The Maven job runs `BuildVersionLockstepTest` on every branch, so a bump on one side alone goes
red and waits for a human. A pom property needs no Gradle edit at all. For the rest, how the
other side moves depends on how Gradle declares the value. A library declared as a dependency
coordinate on both sides (springdoc, datasource-proxy, jdbc-url-parser, cron-utils, axe-core)
gets a `gradle` PR next to the `maven` one, and the human lands the two together. A tool Gradle
pins through `toolVersion` or `palantirJavaFormat(...)` has no Gradle PR, because
Dependabot's `gradle` ecosystem bumps dependency coordinates only. The Maven PR goes red alone,
and the human adds the Gradle edit on that branch. On every `dependabot/*` branch
`build-on-push` adds a `gradle-build` job next to the Maven job, so that Gradle edit builds
before the merge. A Gradle bump with no Maven twin, such as a `buildSrc` plugin or the
wrapper, auto-merges once both jobs are green.

### Reproducibility and artifact parity

Both builds are self-reproducible, producing byte-identical jars across rebuilds, via
`project.build.outputTimestamp` (Maven) and `preserveFileTimestamps=false` +
`reproducibleFileOrder=true` (Gradle). The testing-app's
`build-info.properties`/`git.properties` build times take that same pom property on both
sides.

Across systems, verified by building each twice and cross-diffing, every class file and
resource in the published jars is byte-identical. The expected differences are
`META-INF/MANIFEST.MF` (Maven adds `Created-By`/`Build-Jdk-Spec`) and Maven's
`META-INF/maven/**`. The testing-app boot jar also differs in dependency resolution:
Maven's nearest-wins settles springdoc's transitive Jackson at the version the Boot BOM
pins, where Gradle's highest-wins takes the newer one springdoc asks for and pulls in
`aopalliance` besides. Acceptable for an unpublished sample app, and the first thing to
reconcile if the Gradle build is ever promoted.

Not ported, deliberately, because the Gradle build is local-first: the `peekaboot-publish`
and `peekaboot-release` profiles, and the CI wiring around them. Nor the dependency check, which guards a Maven
resolution behaviour Gradle does not have. Gradle takes the highest requested version, so
it cannot settle a transitive below what a dependent asked for.

The remaining gates are mirrored too: `-Werror` in the conventions plugin, and a
`check`-bound task apiece for the starter's
[optional-dependency ban](#the-starters-optional-dependency-contract) and the
[configuration-metadata check](#the-configuration-metadata-check). Gradle has no enforcer
plugin, so both are plain verification tasks over the resolved runtime classpath and the
compiler's output directory. Gradle's `compileOnly` cannot leak the way a lost Maven
`<optional>` can, but a dependency moved to `api` or `implementation` would, so the ban is
worth having on both sides.

## Compilation

- `release 25`; `peekaboot-testing-app` additionally compiles with `-parameters`.
- `-Werror`. Error Prone reports its findings as warnings, so this is what makes it a gate
  rather than build noise.
- `<proc>full</proc>`. JDK 23+ no longer runs classpath annotation processors implicitly,
  and `spring-boot-configuration-processor` has to keep running; the
  [configuration-metadata check](#the-configuration-metadata-check) verifies the outcome.
- `annotationProcessorPaths` replaces classpath scanning entirely, so both processors are
  listed explicitly: `error_prone_core` and `spring-boot-configuration-processor`.
- `.mvn/jvm.config` carries the `--add-exports`/`--add-opens` into `jdk.compiler` that
  Error Prone needs since JDK 16 sealed those packages. They apply to the *Maven* JVM
  because javac is not forked. Deleting that file breaks every compile.

## Quality gates

| Gate | Phase | Plugin (tool) | Config | Scope |
| --- | --- | --- | --- | --- |
| Formatting | `verify` | `spotless-maven-plugin` (palantir-java-format) | inline in the POM | Java |
| Bug patterns, compile-time | `compile` | `error_prone_core` via the compiler plugin | defaults | main + test |
| Bug patterns, bytecode | `verify` | `spotbugs-maven-plugin` | `config/spotbugs-exclude.xml` | main classes |
| Complexity metrics | `verify` | `maven-checkstyle-plugin` (checkstyle) | `config/checkstyle.xml` | main only |
| Code smells | `verify` | `maven-pmd-plugin` (PMD) | `config/pmd-ruleset.xml` | main Java |
| Frontend lint | `verify` | `frontend-maven-plugin` (ESLint, stylelint, html-validate) | `eslint.config.mjs`, `stylelint.config.mjs`, `.htmlvalidate.json` in `peekaboot-frontend/` | that module's JS, CSS and HTML, `vendor/` excluded |
| Coverage floor | `verify` | `jacoco-maven-plugin` | inline in `peekaboot-coverage/pom.xml` | all measured classes, reactor-wide |
| Dependency upper bounds | `validate` | `maven-enforcer-plugin` | inline in the parent POM | every module's resolved closure |
| Optional-dependency leaks | `validate` | `maven-enforcer-plugin` | inline in `peekaboot-spring-boot-starter/pom.xml` | the starter's transitive closure |
| Configuration metadata present | `process-classes` | `maven-enforcer-plugin` | inline in `peekaboot-backend/pom.xml` | `peekaboot-backend/target/classes` |

Each gate's plugin and tool version is pinned in the root `pom.xml`. The Gradle build reads
the ones declared as properties and spells the rest out again in
`buildSrc/src/main/kotlin/peekaboot.java-conventions.gradle.kts` (see [Lockstep](#lockstep)). The
frontend gate is the exception on both counts: it is declared in `peekaboot-frontend`'s own
POM and build script, because the root `<plugins>` block reaches modules that have no
`package.json` for `npm ci` to read, and its tool versions live in that module's
`package.json` and lockfile, which both build systems install from.

`-Dpeekaboot.frontend.lint.skip=true` turns the frontend gate off. It is the switch CI's
snapshot-install step uses; `-DskipTests` and `-Dmaven.test.skip` do not reach it, because
frontend-maven-plugin honours those only in the `test` and `integration-test` phases.

Each config file explains its own exclusions; the short version:

- **Checkstyle is metrics-only** (cyclomatic/NPath/boolean complexity, NCSS, method
  length, parameter count, fan-out) at stock thresholds. Formatting belongs to Spotless and
  bug patterns to Error Prone/SpotBugs; none of it is duplicated. Test sources are
  excluded, because test data builders legitimately mirror wide domain records.
- **PMD** is `rulesets/java/quickstart.xml` with two rules dropped and two added.
  `AvoidUsingVolatile` is dropped because SpotBugs' `AT_STALE_THREAD_WRITE_OF_PRIMITIVE`
  demands exactly that modifier, so the two tools would deadlock. `GuardLogStatement` is
  dropped because parameterized SLF4J calls with cheap arguments are the project style.
  Added: `InvalidLogMessageFormat`, because a placeholder that does not match its arguments
  compiles and runs, and `UnnecessaryWarningSuppression`, which fails the build on a PMD
  suppression that no longer suppresses anything.
- **SpotBugs** excludes `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` globally. Measured, not assumed:
  every exposure the pair reports across the backend falls in a category the rule cannot
  help with. Most are JSON carriers, which must hold the nulls real actuator data contains
  and so cannot use `List.copyOf`/`Map.copyOf`. The rest are constructors storing injected
  collaborators, `@ConfigurationProperties` accessors and framework contracts. No store or
  service leaks a live collection. `DMI_HARDCODED_ABSOLUTE_FILENAME` is scoped to
  `ContainerRuntime$Signals`, the only class that raises it.
- **The frontend gate** runs ESLint, stylelint and html-validate from
  `peekaboot-frontend/package.json`. It went in after a missing `import` passed every other
  gate and surfaced as 26 Playwright timeouts. PMD's `pmd-javascript` module was never an
  option: its Rhino parser throws `NullPointerException` on the destructuring the frontend
  uses throughout. Three things about it are load-bearing rather than taste. ESLint runs
  the frontend's modules under `sourceType: "module"`, without which an unimported
  identifier reads as a possible implicit global and `no-undef` says nothing; the three
  files a `<script>` tag loads without `type="module"` are listed and linted as scripts.
  `vendor/` is excluded because uplot's minified bundle reports seven errors of its own.
  And `--pk-gantt-depth`, the one custom property JavaScript sets rather than CSS, is
  registered in `js-defined-css-tokens.json`, because stylelint cannot see a `setProperty`
  call and reports the three rules that read it as unknown without it.

Config paths resolve through `${maven.multiModuleProjectDirectory}`, which Maven sets to
the directory holding `.mvn/`. That makes them work from the repo root and from inside a
module directory alike.

### The dependency check

`maven-enforcer-plugin`'s `requireUpperBoundDeps` runs at `validate` in every module that
inherits the parent. It fails when Maven's nearest-wins resolution settles a transitive
*below* the version one of its dependents asked for. That is the shape that reaches a
consumer as a `NoSuchMethodError`, and a BOM import makes it easy to introduce. The
published modules are clean. The reactor's one violation is springdoc's swagger chain
wanting a newer Jackson than the Boot BOM pins, and it lives in the sample app, which does
not inherit the parent. `peekaboot-coverage` skips the rule for the same reason: its
dependencies exist to force build order, so they drag the sample app's closure in with
them.

### The starter's optional-dependency contract

The `<optional>` declarations across `peekaboot-backend` and
`peekaboot-spring-boot-autoconfigure` promise a consumer that the artifacts they mark
arrive from the host application's own starters. Both modules carry the flag for the
artifacts they share, such as `jakarta.servlet-api` and `logback-classic`, because Maven
does not propagate `<optional>` transitively. A module that touches the class at compile
time needs its own copy of the flag to keep the promise down the chain to the starter.
The promise matters because the auto-configuration reads the classpath: lose the flag on
HikariCP and `@ConditionalOnClass(HikariDataSource.class)` fires inside an application
running a different pool.

`bannedDependencies` with `searchTransitive` on `peekaboot-spring-boot-starter` is where
that becomes checkable, because the starter is what a consumer actually depends on. It
bans `jakarta.servlet:jakarta.servlet-api`, `org.springframework:spring-webmvc`,
`org.springframework.boot:spring-boot-web-server`, `com.zaxxer:HikariCP` and
`org.hibernate.orm:hibernate-core`. The others
cannot be banned, because the starter's own dependencies bring them: logback through
`spring-boot-starter-logging`, `spring-boot-health` and `micrometer-observation` through
`spring-boot-starter-actuator`, the OpenTelemetry SDK and
`spring-boot-micrometer-observation` through `spring-boot-starter-opentelemetry`. Re-check
the split after a dependency change with
`./mvnw -pl peekaboot-spring-boot-starter -am dependency:tree`.

### The configuration-metadata check

`spring-boot-configuration-processor` turns every `@ConfigurationProperties` class in
`peekaboot-backend` into `META-INF/spring-configuration-metadata.json`, which is what an
IDE reads to complete and document peekaboot's settings. It runs only because the parent
sets `<proc>full</proc>` and lists the processor in `annotationProcessorPaths`; lose
either and the jar ships without metadata, silently. `requireFilesExist` at
`process-classes` fails the build instead, early enough that `./mvnw test` catches it.

### The coverage gate

`peekaboot-coverage` holds it: line >= 90%, branch >= 75% over every published class,
measured on the merged data of the whole reactor. The floors sit well below actual
coverage on purpose. They catch a substantial regression, not a few uncovered lines. Both
are properties (`jacoco.min.line` and `jacoco.min.branch`), so raising the floor is a
one-line commit. Lowering one to make a build pass is not a fix.

Three deliberate things about that module:

- **It exists because `jacoco:check` only ever analyses the classes of the module it runs
  in**, and there is no `check-aggregate` goal. So the gated classes are physically collected
  here. `unpack-dependencies` unpacks the measured modules' jars into `target/classes`, and
  `check` runs against those. JaCoCo matches execution data to classes by a bytecode hash, so
  the unpacked copies are the classes the tests ran.
- **It depends on every measured module, including the sample app**, which is what forces
  it to build last, after every `jacoco.exec` has been written. `peekaboot-testing-app`'s
  own code is excluded from the report and the gate, but its tests run peekaboot
  in-process, so its execution data carries a meaningful share of the backend's covered
  lines. Per-module reporting would throw that away.
- **A missing data file makes `jacoco:check` pass silently**, turning the gate into
  decoration the moment the merge breaks. `maven-enforcer-plugin` fails the build first if
  `target/jacoco-merged.exec` is absent. Pass `-Djacoco.skip=true` to turn the agent, the
  check and the guard off together.

The aggregate HTML report lands at
`peekaboot-coverage/target/site/jacoco-aggregate/index.html`. Nothing publishes it.

### Spotless

Spotless formats and checks every Java file. Local builds rewrite your working tree: the
`spotless-apply-local` profile is active whenever `env.CI` is unset and runs
`spotless:apply` at `process-sources`, so `spotless:check` can never surprise you at
`verify`. On CI (`CI=true` on GitHub Actions) the profile is off and unformatted code fails
the build instead of being silently fixed inside a sandbox that never pushes the result
back. Set `CI=1` locally to reproduce that.

## Tests

Surefire and failsafe, JUnit 5 + AssertJ. Conventions, the pristine-output policy
and the Playwright teardown rule live in [`docs/TESTING.md`](docs/TESTING.md). This section
covers only the build mechanics.

- Test sources exist in `peekaboot-test-support`, `peekaboot-backend`,
  `peekaboot-spring-boot-autoconfigure` and `peekaboot-testing-app`. The parent runs `maven-dependency-plugin:properties` in every
  module and gives surefire and failsafe one managed `argLine`,
  `@{jacocoArgLine} -javaagent:${org.mockito:mockito-core:jar}`. The agent keeps
  Mockito's inline mock-maker from self-attaching and warning about it; the
  `@{jacocoArgLine}` prefix late-binds the coverage agent so both survive. A module
  without tests never forks a test JVM, so the placeholder left unresolved there is
  harmless. `peekaboot-testing-app` carries its own copy, since it does not inherit the
  parent.
- `peekaboot-testing-app`'s tests activate the `test` profile: H2 instead of PostgreSQL,
  Docker Compose off. Three ITs still start a container through Testcontainers, so
  `./mvnw verify` needs Docker (see [Prerequisites](#prerequisites)).
- Its Playwright tests drive real headless Chromium. The driver downloads it on first use;
  install it explicitly with the `exec:java` invocation in the module README if that fails.
- The shared test helpers live in `peekaboot-test-support`. Its
  [README](peekaboot-test-support/README.md) names them and their consumers, and says why
  a module rather than a `-tests` jar.
- The parent sets the runners' includes explicitly: `*Test` and `*Tests` for surefire,
  `*IT` for failsafe, the same patterns as the Gradle `test`/`integrationTest` tasks.
  The defaults would also take `Test*`, `*TestCase`, `IT*` and `*ITCase`, which Gradle
  would not, so a class named that way would run under one build only. The testing-app
  keeps Boot's defaults; every class the suite runs there is a `*IT`.
- Two classes are excluded from normal runs by *naming*, not configuration:
  `ScreenshotCapture` (a website-screenshot tool that does need Docker) and
  `TraceWritePathBenchmark`. Neither matches those includes. Running either is Maven
  only: `-Dtest=` widens Surefire's includes, while Gradle's `--tests` only filters within
  a task's own includes, so no Gradle task can reach them.
- Never combine `-am` with `-Dtest`.

## CI

The workflows live under `.github/workflows/`. Every workflow that builds uses the checked-in
`./mvnw`, and every third-party action is pinned to a commit SHA with the tag in a trailing
comment; Dependabot's `github-actions` updates move the pins. `osslabz/github-actions` is the
exception, tracked by its moving `@v1` tag: SHA pinning defends against a third-party action
being retagged under you, which does not apply to an action in an organisation of our own, and
pinning it here would leave this repository the one consumer that never receives its fixes.

### `.github/actions/prepare-build`

The steps the build workflows share, as a composite action: JDK 25 (temurin) with the
Maven cache, `~/.cache/ms-playwright` cached under a key derived from the testing-app's
`playwright.version` property and the engines asked for (only a Playwright bump changes the
browser build), `~/.npm` cached on the frontend lockfile's hash, the reactor's SNAPSHOTs
installed, then those browsers installed. The Node
distribution needs no cache entry of its own: frontend-maven-plugin fetches it as a Maven
artifact, which `setup-java`'s cache already covers. The `browsers` input names them and defaults to `chromium`, which
leaves the push build as it was; `with-deps` adds `--with-deps`, the flag that apt-installs
the engines' system libraries and wants passwordless sudo. The checkout
stays in each workflow: a local action resolves from the runner's workspace, so it cannot
run before the checkout that puts it there. Its inputs hand a server id and credential
variable names on to `setup-java`, and the release workflow's GPG key with them.
`build-on-push` and `release` both name the `central` server, because both publish; only
the release signs. `cross-browser` passes none and gets `setup-java`'s defaults.

Both publishing workflows also pass `server-origin: https://central.sonatype.com`. Maven 3.10
sends a server's credentials only to the origins declared for its id, and for `central` that is
`repo.maven.apache.org` alone, so without it every upload goes out anonymous and Central answers
401. The action hands it to `setup-java`'s `mvn-server-repository-origins`, which writes
`<repositoryOrigins>` into the generated `settings.xml`.

The Chromium install is split into two steps on purpose. `exec:java` ignores `-pl` scoping
when combined with `-am`: it runs the goal against every upstream reactor module too and
fails on the first one without Playwright on its classpath. So the reactor's SNAPSHOTs are
installed first (`-pl peekaboot-testing-app -am install -Dmaven.test.skip=true`, with the
static-analysis gates, the frontend lint gate and the sources/javadoc jars skipped because
the `verify` that follows runs them all anyway), and the plain `exec:java` call resolves
against the local repo afterwards. That ad-hoc call is why the testing-app pom pins `exec-maven-plugin` in
`pluginManagement`: `spring-boot-starter-parent` does not manage it, and an unpinned prefix
invocation resolves whatever is latest that day.

### `build-on-push.yml`

Runs on every branch except `main`, and on a `workflow_dispatch`, which
`dependabot-pr-auto-merge` sends for `dev` after each merge. It checks out with
`fetch-depth: 0` for the subject gate and git-cliff, gates the pushed commit subjects first,
then runs `prepare-build`, sets the branch's snapshot version and runs
`./mvnw --batch-mode clean deploy -P peekaboot-publish`.
After the build it installs git-cliff, runs the release-notes tests and the tests of
`.github/central/await-release.sh` (`.github/central/test/run.sh`, against the real repo1),
and writes the pending notes into the run summary. The job keeps `contents: read`, because
publishing authenticates to Central with the secrets the release uses rather than
`GITHUB_TOKEN`.
See [Snapshots](#snapshots) and [Release notes](#release-notes).

The goal follows the publishing secret. A run that can read `OSSRH_TOKEN` runs `deploy`, and a
run that cannot runs `verify`, checking what every other branch publishes. Dependabot's runs
get Dependabot secrets only, and none is set, so they verify. Whether the build dispatched
after a Dependabot merge counts as Dependabot's run is GitHub's call: if it does, `dev`'s
snapshot waits for the next push to `dev`. A deploy without the secret would fail on Central's
401 instead.

A `lint` job runs next to it on every push: actionlint over the workflows and shellcheck over
every `*.sh` under `.github`. Both are downloaded at a pinned version and checked against its
SHA-256, and actionlint is handed that shellcheck for the workflows' `run:` blocks.

On a `dependabot/*` branch a second job, `gradle-build`, runs next to the Maven job, because
the Maven build compiles nothing from the Gradle files. A Gradle-ecosystem PR changes them,
and a red Maven tool bump gets its Gradle twin pushed onto the Maven PR's branch by hand, so
every Dependabot ecosystem runs it. It validates the wrapper jar with
`gradle/actions/wrapper-validation` first, since those branches can replace the jar
`./gradlew` executes, then runs `prepare-build` and `./gradlew build`. See
[Lockstep](#lockstep).

### `draft-release-notes.yml`

Pushes to `dev`, and a dispatch on `dev` after each Dependabot merge (see
[`dependabot-pr-auto-merge.yml`](#dependabot-pr-auto-mergeyml)). A dispatch from any other
ref fails before the checkout, since the job overwrites the one draft. It renders the
unreleased notes and upserts a draft release named `Unreleased` on the placeholder tag name
`unreleased`, so the notes for the cycle in progress are readable on the Releases page rather
than only inside a workflow run. A draft
carries no git tag, since GitHub creates the ref only on publish. So it has no
`/releases/tag/unreleased` URL - that 404s. GitHub parks it under
`/releases/tag/untagged-<hash>`; find it at the top of the Releases page, badged
Draft. It is visible only to accounts with write access, so a signed-out visitor
sees nothing there.

Separate from `build-on-push` because it needs `contents: write`, and that workflow runs on
every branch including Dependabot's, whose token is read-only.

The release job deletes the draft once the real release exists. It does not reappear until
the next real commit on `dev`: the release job's own commits are pushed with `GITHUB_TOKEN`,
and pushes made with that token trigger no workflows.

### `cross-browser.yml`

Nightly at 03:17 UTC, and on demand through `workflow_dispatch`: `peekaboot-testing-app`'s
`clean verify` once per Playwright engine, one `ubuntu-latest` job each, `fail-fast: false` so
a red engine does not cancel the other two. `-Dpeekaboot.it.browser=<engine>` selects it and
`prepare-build` installs that one engine with `--with-deps`, which WebKit on Linux needs for
its ~60 system libraries. Firefox and WebKit hold back the `chromium-only` tests.

The webkit leg drives Playwright's own WebKit build: the engine Safari is built on, at a
different version and feature set. It is not Safari, and a green leg says nothing about
Safari. It is separate from `build-on-push` so that a red engine here never fails a push
build.

### `release.yml`

See [Releasing](#releasing).

### `dependabot-pr-auto-merge.yml`

Waits for the `build-on-push` run of a Dependabot PR's head commit with `gh run watch`, then
approves the PR and rebase-merges it into `dev`. It waits itself rather than using
`gh pr merge --auto`, which only arms when a branch rule holds the pull request open; `dev`
carries no such rule, for the reason under [Releasing](#releasing). A red or cancelled build
leaves the pull request open and unapproved for a human. The merge condition is an
allow-list. It admits a patch or minor update from the `github_actions`, `maven`, `gradle` or
`npm_and_yarn` ecosystem, outside the `spring-boot` dependency group. Anything else waits for
a human, so a semver-major update does, and so does a pull request whose update type
Dependabot did not report. The watch covers every job in the run, so every PR also waits for
the `gradle-build` job. Boot is a
compatibility event rather than a bump, because peekaboot implements
`EndpointExposureOutcomeContributor`, constructs actuator endpoint objects directly and
depends on Boot's property-source ordering. That whole line
arrives as one grouped PR: its version is declared twice on the Maven side, and a bump
landing in one place only leaves the reactor building two Boot versions. Dependabot watches
Maven, Gradle and the frontend's npm packages daily, GitHub Actions weekly. Every ecosystem
holds an update back for seven days after its release, since a merged update runs in the `dev`
build that holds the publishing token; security updates are not delayed.

The workflow derives from `osslabz/github-actions`' shared
`.github/workflows/dependabot-auto-merge.yml` and keeps its structure. It keeps its own copy
because the shared one merges only Maven and GitHub Actions updates, merges action majors and
does not hold the `spring-boot` group back. Action majors wait here because `release.yml` and
this workflow run actions `build-on-push` never exercises. `metadata` runs
`dependabot/fetch-metadata` with `pull-requests: read` alone. `merge` holds the write
permissions and runs only for a pull request Dependabot opened and pushed; the allow-list
sits on its merge step. `build-merged-dev` dispatches `build-on-push` and
`draft-release-notes` on `dev` once the merge succeeded, where the shared one dispatches the
build alone. The
merge pushes `dev` with `GITHUB_TOKEN`, and a push with that token starts no workflow, so
without the dispatch the merged `dev` would get no build, no snapshot and no draft notes.

Branch model: `dev` is the default and integration branch, and the only branch that
originates commits. `main` is a pointer to the last released commit; the release job
fast-forwards it and nothing else writes to it. The `green-default-branch` ruleset on `dev`
forbids deletion and requires linear history. Its bypass list holds the organisation admins
and the repository admin role, both `bypass_mode: always`, so those two rules bind CI and the
merge button rather than the owner. Nothing requires a status check on `dev`; see
[Releasing](#releasing) for why one cannot coexist with an automated release.

## Snapshots

Every push to a branch other than `main` publishes the reactor to Central's snapshot
repository, so a downstream project can build against unreleased work. `dev` publishes the
plain version out of the root pom; every other branch folds its name into it, because all
branches carry the same version and a single coordinate would leave only the last push
standing.

```
dev                          0.2.1-SNAPSHOT
feat/async-instrumentation   0.2.1-feat-async-instrumentation-SNAPSHOT
```

The `snapshot-version` action in
[osslabz/github-actions](https://github.com/osslabz/github-actions) derives that version and
applies it to the reactor for the length of the run; the poms in git never carry it. The same
action runs in the osslabz repositories, which is why the integration branch is an input
rather than assumed - most of them settle on `dev`, `bitcoin-commons` and `lnd-rest-client` on
`main`. It owns its own tests and the `versions:set` invocation, including the
`-DupdateBuildOutputTimestampPolicy=never` without which the plugin puts the run's own clock
where the reproducible-build instant belongs, and it names `versions-maven-plugin` in full so
this pom pins nothing for it.

`peekaboot-publish` holds `central-publishing-maven-plugin` and nothing else, which is the
whole mechanism: the plugin reads the version and routes a `-SNAPSHOT` to the snapshot
repository by itself. The signature stays behind in `peekaboot-release`, because Central
validates a release and takes a snapshot as it comes. A release activates both.

What lands is what a release lands, jar, sources and javadoc alike: the plugin publishes
every attached artifact, and the source and javadoc jars are attached by the main build
rather than by a profile. The module set matches too - `peekaboot-test-support` and
`peekaboot-coverage` carry `skipPublishing`, `peekaboot-testing-app` carries
`maven.deploy.skip`.

Consuming a snapshot needs the repository declared, where a release needs nothing:

```xml
<repository>
    <id>central-portal-snapshots</id>
    <url>https://central.sonatype.com/repository/maven-snapshots/</url>
    <releases><enabled>false</enabled></releases>
    <snapshots><enabled>true</enabled></snapshots>
</repository>
```

```kotlin
repositories {
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
    mavenCentral()
}
```

Reading it is anonymous. Central expires a snapshot around 90 days after it lands, and the
namespace has to have snapshots switched on in the Portal before the first deploy will be
accepted - a one-off toggle on `org.peekaboot` under Namespaces, with no API behind it.

## Releasing

Signing and the release mechanics sit in the `peekaboot-release` profile, publishing itself in
`peekaboot-publish`; a release activates both, and the push build activates publishing alone
(see [Snapshots](#snapshots)). A local build activates neither profile nor a `deploy` goal, so
it never signs or publishes anything. Releases start by hand: run the `release` workflow from the
Actions tab with `dev` selected, and it fails immediately if dispatched from anything else.
Leave `releaseVersion` empty unless git-cliff reads the bump wrong. The run does:

1. A preflight that fails the run before anything is built when `dev` moved since the
   dispatch, when no commit but the next development version follows the last tag, or when
   `main` is not an ancestor of `dev` and so could not fast-forward.
2. The release version resolved with `git-cliff --bumped-version`. `CHANGELOG.md` regenerated
   whole against it and staged, and `README.md`'s dependency snippet rewritten to it and
   staged. See [Release notes](#release-notes).
3. `./mvnw -P peekaboot-release release:prepare -DreleaseVersion=<x.y.z>`, whose own commit
   picks the staged changelog up, so version bump, changelog and tag are one commit. Its
   `clean verify` is the release's one full build.
4. `./mvnw -P peekaboot-release release:perform`, then a wait until the release is on repo1.
5. `main` fast-forwarded to the tagged commit, so it names exactly what was published.
   Nothing merges back, because `main` originates no commits of its own.
6. Grouped release notes for the new tag rendered by git-cliff into the release body, and
   the `unreleased` draft deleted.
7. The docs site published: `_data/releases.json` and `_config.yml`'s `peekaboot_version`
   committed to that repo's `dev`, its `main` fast-forwarded so Pages rebuilds, and the same
   bare version tagged there.
8. `build-on-push` dispatched on `dev`, so the next development version gets its build and
   snapshot. The release's own pushes use `GITHUB_TOKEN`, which starts no workflow.

`release:prepare` pushes to `dev` with `GITHUB_TOKEN`, and that works only because the ruleset
requires no status check. A required check refuses any push that does not carry one, and a
`GITHUB_TOKEN` push never carries one, because that token starts no workflows. The usual
escape is a ruleset bypass, but GitHub Actions is not an eligible bypass actor at all: the
list admits repository admins, organisation and enterprise owners, the maintain or write role,
teams, GitHub Apps and Dependabot. So a required check on `dev` and an automated release
cannot coexist, short of running the release under a GitHub App or a personal token.

That check used to exist to arm auto-merge on Dependabot pull requests, which is why
[`dependabot-pr-auto-merge.yml`](#dependabot-pr-auto-mergeyml) now waits for the build itself.

Nothing is left to do by hand afterwards. The Gradle build needs no step either, since it
derives the version and the build instant from the poms.

Both places that spell a version out are rewritten by the job. `README.md`'s dependency
snippet is staged before `release:prepare`, so it rides into the tagged commit beside the
changelog. The docs site's `_config.yml` carries `peekaboot_version`, which every dependency
snippet on the site reads, and it is committed to that repo's `dev` with the release data.
Each rewrite is a `sed` followed by a `grep`, because a pattern that stops matching exits 0
and would otherwise publish a stale version behind a green build.

The site repo follows the same branch model as this one: `dev` is where commits land and
`main` is fast-forwarded onto them, which is what triggers the Pages rebuild. Its tag is the
same bare `x.y.z` as the app's, so a site commit can be traced to the release it shipped with.

`peekaboot-release` adds `maven-release-plugin`, which the workflow drives with an explicit
`-DreleaseVersion`; see [How the next version is chosen](#how-the-next-version-is-chosen).
Tags are bare `@{project.version}`; release commits are prefixed `[release]`. It also
GPG-signs with `raphael@peekaboot.org`. `releaseProfiles` names both profiles, because the
fork `release:perform` starts is what runs `deploy` and it needs the publishing plugin as
well as the signature.

`peekaboot-publish` contributes `central-publishing-maven-plugin`, which on a release runs
with `autoPublish=true` / `waitUntil=uploaded`. Any later wait polls the Portal's status
endpoint, and the plugin fails the build on the first non-2xx answer, even though the
deployment still publishes. So `release:perform` returns at the upload, and the job's
`await-maven-central` step polls repo1 for the starter's pom, retrying through errors for up
to 30 minutes. The post-publish steps run only once it is there. Flipping `autoPublish` to
`false` rehearses an upload instead: the deployment waits for a manual publish in the
Portal, and `await-maven-central` times out. Neither setting reaches the snapshot path,
which never calls the Portal.

The sources and javadoc jars are *not* release-only. Both are attached on every build of
the published modules, and javadoc runs with `doclint` at `all,-missing` and fails on an
error, so a broken `@link` surfaces at `./mvnw package` rather than after `release:prepare`
has pushed the tag.

Three modules stay out of the bundle, by two different switches. The publishing plugin
binds itself to `deploy` in every module that inherits the profile and ignores
`maven.deploy.skip`. So `peekaboot-coverage` and `peekaboot-test-support` opt out with
`skipPublishing`, its per-module switch, which filters only that module's own artifacts;
the bundle is still uploaded from `peekaboot-coverage`, the reactor's last module.
`peekaboot-testing-app` never sees the plugin at all. The profile is undefined in its
`spring-boot-starter-parent` pom, so the plain `maven-deploy-plugin` runs for it, and
`maven.deploy.skip` keeps the sample app out.

`release:prepare` bumps the POMs to the release version, runs its `preparationGoals`
(`clean verify`) against that tree, then commits it, tags it and commits the next
`-SNAPSHOT` version; it deploys nothing. `release:perform` checks the tag out into
`target/checkout` and runs the configured `<goals>` (`deploy`) there, which is where
signing and the upload to Central happen. The workflow passes it
`-Darguments="-DskipTests -DskipITs -Djacoco.skip=true"`. Failsafe ignores `-DskipTests`,
so `-DskipITs` is what keeps the Playwright suite out. That tree passed `verify` in
`preparationGoals`, so a second run would only repeat the Playwright suite. The static-analysis gates, both
dependency checks and the configuration-metadata check still run.

The changelog reaches the tagged commit because that commit is a plain `git commit` over
the whole index: maven-scm stages the POMs and then commits everything staged. So the
workflow renders and stages `CHANGELOG.md` before prepare runs, and
`checkModificationExcludes` stops the modification check refusing the staged file. No
version of maven-scm documents that, so `verify-release-commit` greps the new tag and
fails the job while the only damage is a tag nobody has consumed.

Reproducibility depends on `project.build.outputTimestamp` being pinned in the root pom and
in the testing-app's, and on every plugin version being explicit. That includes the
lifecycle plugins Maven would otherwise bind on its own. The testing-app pins, in its own
`pluginManagement`, each plugin whose root pom version differs from the one
`spring-boot-dependencies` manages, so Dependabot bumps both poms in one pull request and a
reactor build runs one version of each.

### Release notes

Everything lives in `.github/release-notes/`. `cliff.toml` classifies commits by
conventional-commit type into numbered groups and holds the markdown template, `site.jq`
reshapes git-cliff's `--context` JSON into the website's data file, and `test/` holds a
fixture repo with golden files.

`render.sh` is the only place git-cliff is invoked, and it refuses to run without the
config. That guard earns its keep: `git-cliff --config <missing>` merely warns, falls back
to its own built-in grouping and still exits 0, so a mistyped path would publish wrongly
grouped notes with nothing failing.

Commits sharing a subject collapse to one entry even when their bodies differ, because in
conventional mode git-cliff's `commit.message` holds only the description. Merge commits and
the `[release]` commits drop out via `filter_unconventional`, neither being conventional.
`filter_commits` is on with no `.*` catch-all, so an unrecognised type is dropped rather than
bucketed — which is what the gate is for.

The push gate is `osslabz/github-actions/commit-subject-check`, which `build-on-push` runs
with `default-branch: dev`. It rejects any non-merge subject in the pushed range that is not a
conventional commit, and skips a forced push and a dispatched run, which have no meaningful
range. The subjects matter beyond the notes, because git-cliff derives the release version
from them too. A subject it cannot parse is a change it cannot weigh. The gate does not
enforce the 50-character subject limit, because Dependabot's own `build(deps): bump ...` lines
routinely exceed it.

Parser order in `cliff.toml` is load-bearing, because the first matching parser wins. The
`testing-app` and `test` scopes of `feat`, `fix` and `perf` must stay above `^feat`, so test
harness work lands under Tests. The runtime dependency allow-list must stay above the
generic `^build`, or every dependency bump lands in "Build, CI and chores". Group
numbers must stay zero-padded to two digits, because git-cliff sorts group names as strings.
An unpadded `<!-- 10 -->` would sort ahead of `<!-- 2 -->`. The parsers at the top of the list
name a commit by its sha. Each is a commit typed `fix:` or `feat:` that changed nothing a user
could hit, such as a test or CI fix. History is not rewritten, so the generator regroups or
skips them, and the harness checks every one of them on the real history.

The website shows released versions only. `site.jq` builds a "Breaking changes" section from
the `breaking` flag, then groups `01`-`04`, features, bug fixes, performance and dependency
upgrades. `site.jq` hardcodes that cutoff, so renumbering those groups means editing both
files. A breaking commit appears under Breaking changes alone on the website. Reverts and the
internal groups stay in the GitHub release notes and `CHANGELOG.md`.

"Dependency upgrades" lists only bumps of what a release puts on a user's classpath: the
Spring Boot line, datasource-micrometer, jdbc-url-parser and cron-utils. The `04` parser names
them in an allow-list. Every other `build(deps)` commit lands in "Build, CI and chores", which
covers Maven and Gradle plugins, wrappers, GitHub Actions and test libraries. Dependabot's
`deps-dev` scope cannot draw that line, because it labels many plugin and action bumps `deps`.
`RuntimeDependencyAllowListTest` in `peekaboot-spring-boot-autoconfigure` reads the published
modules' compile and runtime dependencies out of the poms and fails when the allow-list misses
one. A dependency without a version counts as the BOM that manages it.

The tests need git-cliff on `PATH`; the version and its sha512 are pinned in
`.github/actions/install-git-cliff`.

```bash
.github/release-notes/test/run.sh
```

Its expectations are golden files rendered from a fixture repo with fixed commit dates, so
they do not drift as `dev` grows. The fixture carries both spellings of a breaking change, a
breaking change of an internal type, user-facing types scoped to the test harness, a runtime
and a build-only dependency bump, a subject repeated with a different body, a non-conventional
subject, and two tags cut in the same second.

### How the next version is chosen

`git-cliff --bumped-version` takes the highest step among the commits since the most recent
tag matching `tag_pattern` and applies it to that tag's version: `feat:` gives a minor bump,
`type!:` or a `BREAKING CHANGE:` footer a major one, anything else a patch. `cliff.toml`
leaves `[bump]` unset, so those are git-cliff's defaults, and `0.x` is no exception — one
breaking change takes the project to `1.0.0`. Check the answer before releasing:

```bash
.github/release-notes/render.sh --bumped-version
```

The workflow hands that answer to `release:prepare` as `-DreleaseVersion`, so the POM's
`-SNAPSHOT` is a placeholder rather than an input; editing it steers nothing. The plugin
derives the next development version from the released one by incrementing its patch.

The `releaseVersion` dispatch input overrides that answer for a run where git-cliff reads the
bump wrong. An overridden version takes the same `x.y.z` and tag-exists checks as a derived
one.

### Signing and secrets

Secrets consumed by the workflow: `OSSRH_USERNAME`, `OSSRH_TOKEN`, `OSSRH_GPG_SECRET_KEY`,
`OSSRH_GPG_SECRET_KEY_PASSWORD`. The `OSSRH_` prefix is historical: the workflow talks to
the Central Portal (`server-id: central`), and the first two hold a Portal user token. The
names live in the repository settings as well as in the workflow, so a rename touches both.

`OSSRH_GPG_SECRET_KEY` is only half the signing story. Central verifies every `.asc` by
looking the signing key up by fingerprint on a public keyserver, so the public half has to
be distributed before a release. Otherwise the deployment fails validation with
*"Could not find a public key by the key fingerprint"* for every artifact of every module at
once, long after `release:prepare` has tagged and pushed. Distribute it with
`gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>`. Central queries
`keyserver.ubuntu.com`, `keys.openpgp.org` and `pgp.mit.edu`; the last has been unreachable
for years, so treat the first as mandatory and the second as the backup. Confirm it landed
*before* releasing:
`curl -sf "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<FINGERPRINT>"`.

## Things that will bite you

- Local builds reformat your sources mid-build. Expect a dirty tree; that is by design.
- `-DskipTests` skips only the unit tests. Failsafe ignores it, so the testing app's ITs
  still run, and the coverage check then fails on their partial data. Build without tests
  with `-DskipTests -DskipITs -Djacoco.skip=true`.
- `git-commit-id-maven-plugin` is declared only in `peekaboot-testing-app`, the one runnable
  application, and nowhere in the parent. `git.properties` lands at the classpath root and
  Spring resolves `classpath:git.properties` to a single resource, so a library shipping one
  can beat the host application's own file and make the dashboard report Peekaboot's branch
  as the app's. The testing-app pins the version itself with `failOnNoGitDirectory=false`,
  so a worktree whose gitdir pointer does not resolve, or an exported source tree, builds
  fine everywhere.
- The empty `peekaboot-spring-boot-starter` jar is intentional, and so are the empty
  `-sources`/`-javadoc` jars of the starter and the frontend. Do not "fix" the warnings.
