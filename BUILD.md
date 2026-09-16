# Building Peekaboot

Maven is the system of record. One reactor. Static-analysis tools, dependency and output
checks and a coverage floor run as gates across the build lifecycle (see
[Quality gates](#quality-gates)). No Node toolchain, no codegen beyond annotation
processing.

A parallel Gradle build covers the same modules, tests and gates (see
[the Gradle build](#the-parallel-gradle-build) below). CI runs Maven only, so every Maven
statement in this document is authoritative and the Gradle build must be kept in lockstep.

## Prerequisites

| | |
| --- | --- |
| JDK | **25**. `maven.compiler.release=25` / Gradle `options.release = 25`, no toolchains, no fallback |
| Maven / Gradle | The checked-in wrappers. Each fetches its own build tool at the version and SHA-256 pinned in `.mvn/wrapper/maven-wrapper.properties` and `gradle/wrapper/gradle-wrapper.properties`. A local Maven 3.9+ works the same |
| Docker | Only for running the sample app and for `ScreenshotCapture`. Not needed by `mvn verify` |
| Network | First run only: the wrappers fetch their build tool, and Playwright fetches Chromium into `~/.cache/ms-playwright` |

## Commands

```bash
mvn clean verify     # compile + all tests + every gate               <- the real build
mvn clean install    # the same, plus install into ~/.m2
mvn test             # the fast gate: the dependency/output checks + Error Prone
                     # + unit tests only; integration tests (*IT) don't run
                     # before `verify`
mvn spotless:apply   # format (local builds already do this for you)

mvn -pl <module> test -Dtest=<Class>              # one unit-test class; never add -am
mvn -pl <module> verify -Dit.test=<Class>         # one *IT class (runs this module's
                                                  # gates too; that's inherent to verify)
mvn -pl <module> -am verify -Dit.test=<Class> \
    -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false           # the same against uncommitted sibling-module
                                                  # changes: both skip flags, or failsafe fails the
                                                  # first upstream module with "No tests matching pattern"
mvn -pl peekaboot-testing-app spring-boot:run     # sample app on :8093; needs Docker
                                                  # and an `mvn install` beforehand
```

### What each command actually checks

Tests are split by lifecycle. Unit tests live in `*Test` classes and run at `test` under
surefire. Anything that boots a real application (every `@SpringBootTest`, the whole
Playwright suite) lives in `*IT` classes and runs at `integration-test` under failsafe.
That is what makes `mvn test` the fast gate: the dependency/output checks, Error Prone
and every unit test, nothing else. `mvn verify` adds the integration tests and the
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

The Playwright suite is the bulk of a cold `mvn clean verify`. `mvn test` alone is a
fraction of it.

## The reactor

| Module | Artifact | Published | Contains |
| --- | --- | --- | --- |
| `peekaboot-parent` | pom | yes | Shared build config, dependency management (the `spring-boot-dependencies` BOM) |
| `peekaboot-test-support` | jar | **no** (`skipPublishing`, see [Releasing](#releasing)) | `LogCapture` only, consumed at test scope by the backend and autoconfigure suites. See its [README](peekaboot-test-support/README.md) |
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
its build instant, JaCoCo version and Boot parent version with the root pom; every other
change to the parent's build config has to be mirrored there by hand. The one deliberate
exception is the dependency check: the sample app is the module that violates it (see
[the dependency check](#the-dependency-check)), and gating an unpublished sample on a
third-party version clash would buy nothing but two permanent exclusions.

## The parallel Gradle build

`settings.gradle.kts` mirrors the reactor module for module. `./gradlew build` is the
`mvn clean verify` equivalent. It runs unit tests (`test`, `*Test` and `*Tests`) and
integration tests (`integrationTest`, `*IT`, concurrent classes exactly like failsafe, via
`peekaboot.it.threads` in `gradle.properties`). It runs the static-analysis gates at the
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

The module version and the build instant are *derived*, not copied. `settings.gradle.kts`
reads `<version>` and `project.build.outputTimestamp` out of the root `pom.xml` and hands
both to every Gradle project. `maven-release-plugin` rewrites the poms alone, so a
hand-kept copy on the Gradle side went stale at every release and nothing noticed, because
CI runs Maven only. `BuildVersionLockstepTest` fails if such a copy reappears.

One value still lives once in `gradle.properties`: `springBootVersion`, matching the root
pom's `spring-boot.version` and the testing-app's parent version. The convention plugin
turns it into the BOM import every module gets, and `buildSrc` reads it for the Boot
plugin. The same test compares it against the version Maven resolves, because Dependabot
cannot group the two ecosystems into one pull request. Mockito's agent jar takes its
version from that BOM, as Maven does.

Every other shared literal is written on both sides and has to change on both: Error
Prone, palantir, the ratchet SHA, Checkstyle, SpotBugs, JaCoCo, PMD, the coverage floors,
Playwright, springdoc and the testing-app's direct dependencies. Dependabot watches the
`gradle` ecosystem next to `maven`, so bumps arrive as paired PRs. The Gradle half is never
auto-merged, because nothing in CI would build it; check it against the merged Maven bump
by hand.

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

Not ported, deliberately, because the Gradle build is local-first: the `peekaboot-release`
profile, publishing, and CI wiring. Nor the dependency check, which guards a Maven
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
| Formatting | `verify` | `spotless-maven-plugin` (palantir-java-format) | inline in the POM | Java, ratcheted (below) |
| Bug patterns, compile-time | `compile` | `error_prone_core` via the compiler plugin | defaults | main + test |
| Bug patterns, bytecode | `verify` | `spotbugs-maven-plugin` | `config/spotbugs-exclude.xml` | main classes |
| Complexity metrics | `verify` | `maven-checkstyle-plugin` (checkstyle) | `config/checkstyle.xml` | main only |
| Code smells | `verify` | `maven-pmd-plugin` (PMD) | `config/pmd-ruleset.xml` | main Java |
| Coverage floor | `verify` | `jacoco-maven-plugin` | inline in `peekaboot-coverage/pom.xml` | all measured classes, reactor-wide |
| Dependency upper bounds | `validate` | `maven-enforcer-plugin` | inline in the parent POM | every module's resolved closure |
| Optional-dependency leaks | `validate` | `maven-enforcer-plugin` | inline in `peekaboot-spring-boot-starter/pom.xml` | the starter's transitive closure |
| Configuration metadata present | `process-classes` | `maven-enforcer-plugin` | inline in `peekaboot-backend/pom.xml` | `peekaboot-backend/target/classes` |

Each gate's plugin and tool version is pinned in the root `pom.xml`, and again in
`buildSrc/src/main/kotlin/peekaboot.java-conventions.gradle.kts` for the Gradle build.

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
- **Nothing lints the frontend's JS or CSS.** PMD's `pmd-javascript` module is not an
  option: its Rhino parser throws `NullPointerException` on the destructuring the frontend
  uses throughout, and fails outright on the files carrying it. A real JS linter means
  ESLint and therefore a Node toolchain, which this build deliberately does not have. An
  open decision, not an oversight.

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
`org.springframework.boot:spring-boot-web-server` and `com.zaxxer:HikariCP`. The others
cannot be banned, because the starter's own dependencies bring them: logback through
`spring-boot-starter-logging`, `spring-boot-health` and `micrometer-observation` through
`spring-boot-starter-actuator`, the OpenTelemetry SDK and
`spring-boot-micrometer-observation` through `spring-boot-starter-opentelemetry`. Re-check
the split after a dependency change with
`mvn -pl peekaboot-spring-boot-starter -am dependency:tree`.

### The configuration-metadata check

`spring-boot-configuration-processor` turns every `@ConfigurationProperties` class in
`peekaboot-backend` into `META-INF/spring-configuration-metadata.json`, which is what an
IDE reads to complete and document peekaboot's settings. It runs only because the parent
sets `<proc>full</proc>` and lists the processor in `annotationProcessorPaths`; lose
either and the jar ships without metadata, silently. `requireFilesExist` at
`process-classes` fails the build instead, early enough that `mvn test` catches it.

### The coverage gate

`peekaboot-coverage` holds it: line >= 90%, branch >= 75% over every published class,
measured on the merged data of the whole reactor. The floors sit well below actual
coverage on purpose. They catch a substantial regression, not a few uncovered lines. Both
are properties (`jacoco.min.line` and `jacoco.min.branch`), so raising the floor is a
one-line commit. Lowering one to make a build pass is not a fix.

Three deliberate things about that module:

- **It exists because `jacoco:check` only ever analyses the classes of the module it runs
  in**, and there is no `check-aggregate` goal. So the gated classes are physically
  collected here: `unpack-dependencies` unpacks the measured modules' jars into
  `target/classes`, and `check` runs against those. JaCoCo matches execution data to
  classes by a bytecode hash, so the unpacked copies are the classes the tests ran.
- **It depends on every measured module, including the sample app**, which is what forces
  it to build last, after every `jacoco.exec` has been written. `peekaboot-testing-app`'s
  own code is excluded from the report and the gate, but its tests run peekaboot
  in-process, so its execution data carries a meaningful share of the backend's covered
  lines. Per-module reporting would throw that away.
- **A missing data file makes `jacoco:check` pass silently**, turning the gate into
  decoration the moment the merge breaks. `maven-enforcer-plugin` fails the build first if
  `target/jacoco-merged.exec` is absent. So `mvn verify -DskipTests` fails on purpose; pass
  `-Djacoco.skip=true` to turn the agent, the check and the guard off together.

The aggregate HTML report lands at
`peekaboot-coverage/target/site/jacoco-aggregate/index.html`. Nothing publishes it.

### The Spotless ratchet

`ratchetFrom` is pinned to commit `e05e0f97`, the last commit before Spotless landed. Only
files whose content differs from that commit are formatted and checked; untouched legacy
files are left alone. Two consequences:

- **A shallow clone breaks the build.** The ratchet has to resolve that commit. CI uses
  `fetch-depth: 0` for exactly this.
- **Local builds rewrite your working tree.** The `spotless-apply-local` profile is active
  whenever `env.CI` is unset and runs `spotless:apply` at `process-sources`, so
  `spotless:check` can never surprise you at `verify`. On CI (`CI=true` on GitHub Actions)
  the profile is off and unformatted code fails the build instead of being silently fixed
  inside a sandbox that never pushes the result back. Set `CI=1` locally to reproduce that.

## Tests

Surefire and failsafe, JUnit 5 + AssertJ. Conventions, the pristine-output policy
and the Playwright teardown rule live in [`docs/TESTING.md`](docs/TESTING.md). This section
covers only the build mechanics.

- Test sources exist in `peekaboot-backend`, `peekaboot-spring-boot-autoconfigure` and
  `peekaboot-testing-app`. The parent runs `maven-dependency-plugin:properties` in every
  module and gives surefire and failsafe one managed `argLine`,
  `@{jacocoArgLine} -javaagent:${org.mockito:mockito-core:jar}`. The agent keeps
  Mockito's inline mock-maker from self-attaching and warning about it; the
  `@{jacocoArgLine}` prefix late-binds the coverage agent so both survive. A module
  without tests never forks a test JVM, so the placeholder left unresolved there is
  harmless. `peekaboot-testing-app` carries its own copy, since it does not inherit the
  parent.
- `peekaboot-testing-app`'s tests activate the `test` profile: H2 instead of PostgreSQL,
  Docker Compose off. `mvn verify` therefore needs neither Docker nor a database.
- Its Playwright tests drive real headless Chromium. The driver downloads it on first use;
  install it explicitly with the `exec:java` invocation in the module README if that fails.
- `LogCapture` is the only shared test helper, and it lives in `peekaboot-test-support` as
  a plain test-scope dependency of the backend and autoconfigure modules. Why a module
  rather than a `-tests` jar is in
  [peekaboot-test-support/README.md](peekaboot-test-support/README.md). The backend's own
  fixture builders (`Spans`, `SpanNodes`, `TraceTrees`, `RequestCompletedEvents`,
  `TraceStores`) construct backend domain types and stay in its test tree.
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

The workflows live under `.github/workflows/`. Both build workflows use the checked-in
`./mvnw`, and every action is pinned to a commit SHA with the tag in a trailing comment;
Dependabot's `github-actions` updates move the pins. The composite action below has its
own `directory` entry in `dependabot.yml`, because `/` covers `.github/workflows` and a
root `action.yml` only.

### `.github/actions/prepare-build`

The steps the build workflows share, as a composite action: JDK 25 (temurin) with the
Maven cache, `~/.cache/ms-playwright` cached under a key derived from the testing-app's
`playwright.version` property and the engines asked for (a browser build changes with
Playwright, not with any other dependency), the reactor's SNAPSHOTs installed, then those
browsers installed. The `browsers` input names them and defaults to `chromium`, which
leaves the push build as it was; `with-deps` adds `--with-deps`, the flag that apt-installs
the engines' system libraries and wants passwordless sudo. The checkout
stays in each workflow: a local action resolves from the runner's workspace, so it cannot
run before the checkout that puts it there. Its inputs hand the release workflow's Central
server id, credential variable names and GPG key on to `setup-java`; the build workflow
passes none and gets `setup-java`'s defaults.

The Chromium install is split into two steps on purpose. `exec:java` ignores `-pl` scoping
when combined with `-am`: it runs the goal against every upstream reactor module too and
fails on the first one without Playwright on its classpath. So the reactor's SNAPSHOTs are
installed first (`-pl peekaboot-testing-app -am install -Dmaven.test.skip=true`, with the
static-analysis gates and the sources/javadoc jars skipped because the `verify` that
follows runs them all anyway), and the plain `exec:java` call resolves against the local
repo afterwards. That ad-hoc call is why the testing-app pom pins `exec-maven-plugin` in
`pluginManagement`: `spring-boot-starter-parent` does not manage it, and an unpinned prefix
invocation resolves whatever is latest that day.

### `build-on-push.yml`

Runs on every branch except `main`: checkout with `fetch-depth: 0` for the ratchet,
`prepare-build`, then `./mvnw --batch-mode clean verify`. After the build it installs
git-cliff, runs the release-notes tests, gates the pushed commit subjects and writes the
pending notes into the run summary. The job keeps `contents: read`; none of those steps
write anything. See [Release notes](#release-notes).

### `draft-release-notes.yml`

Pushes to `dev` only. Renders the unreleased notes and upserts a draft release named
`Unreleased` on the placeholder tag name `unreleased`, so the notes for the cycle in
progress are readable on the Releases page rather than only inside a workflow run. A draft
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

Nightly at 03:17 UTC, and on demand through `workflow_dispatch`: the same `clean verify`
once per Playwright engine, one `ubuntu-latest` job each, `fail-fast: false` so a red engine
does not cancel the other two. `-Dpeekaboot.it.browser=<engine>` selects it and
`prepare-build` installs that one engine with `--with-deps`, which WebKit on Linux needs for
its ~60 system libraries. Firefox and WebKit hold back the `chromium-only` tests.

The webkit leg drives Playwright's own WebKit build: the engine Safari is built on, at a
different version and feature set. It is not Safari, and a green leg says nothing about
Safari. It is separate from `build-on-push` so that a red engine here never fails a push
build.

### `release.yml`

See [Releasing](#releasing).

### `dependabot-pr-auto-merge.yml`

Approves a Dependabot PR targeting `dev`, waits for its build with `gh pr checks --watch
--fail-fast`, and rebase-merges it once green. It waits itself rather than using
`gh pr merge --auto`, which only arms when a branch rule holds the pull request open; `dev`
carries no such rule, for the reason under [Releasing](#releasing). A red or cancelled build
leaves the pull request open for a human. Three kinds wait for a human anyway: semver-major
updates, every `gradle`-ecosystem update (CI never builds Gradle, so a merged Gradle bump
would be unverified), and the `spring-boot` dependency group. Boot is a
compatibility event rather than a bump, because peekaboot implements
`EndpointExposureOutcomeContributor`, constructs actuator endpoint objects directly and
depends on Boot's property-source ordering. That whole line
arrives as one grouped PR: its version is declared twice on the Maven side, and a bump
landing in one place only leaves the reactor building two Boot versions. Dependabot watches
Maven and Gradle daily, GitHub Actions weekly.

Branch model: `dev` is the default and integration branch, and the only branch that
originates commits. `main` is a pointer to the last released commit; the release job
fast-forwards it and nothing else writes to it. The `green-default-branch` ruleset on `dev`
forbids deletion and requires linear history. Its bypass list holds the organisation admins
and the repository admin role, both `bypass_mode: always`, so those two rules bind CI and the
merge button rather than the owner. Nothing requires a status check on `dev`; see
[Releasing](#releasing) for why one cannot coexist with an automated release.

## Releasing

Everything release-specific sits in the `peekaboot-release` profile; a normal build never
signs or publishes anything. Releases start by hand: run the `release` workflow from the
Actions tab with `dev` selected, and it fails immediately if dispatched from anything else.
Leave `releaseVersion` empty unless git-cliff reads the bump wrong. The run does:

1. `./mvnw --batch-mode verify`
2. The release version resolved with `git-cliff --bumped-version`. `CHANGELOG.md` regenerated
   whole against it and staged, and `README.md`'s dependency snippet rewritten to it and
   staged. See [Release notes](#release-notes).
3. `./mvnw -P peekaboot-release release:prepare -DreleaseVersion=<x.y.z>`, whose own commit
   picks the staged changelog up, so version bump, changelog and tag are one commit.
4. `./mvnw -P peekaboot-release release:perform`
5. `main` fast-forwarded to the tagged commit, so it names exactly what was published.
   Nothing merges back, because `main` originates no commits of its own.
6. Grouped release notes for the new tag rendered by git-cliff into the release body, and
   the `unreleased` draft deleted.
7. The docs site published: `_data/releases.json` and `_config.yml`'s `peekaboot_version`
   committed to that repo's `dev`, its `main` fast-forwarded so Pages rebuilds, and the same
   bare version tagged there.

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

The profile adds `maven-release-plugin`, which the workflow drives with an explicit
`-DreleaseVersion`; see [How the next version is chosen](#how-the-next-version-is-chosen).
Tags are bare `@{project.version}`; release commits are prefixed `[release]`. It also
GPG-signs with `raphael@peekaboot.org` and publishes through
`central-publishing-maven-plugin`, which runs with `autoPublish=true` /
`waitUntil=published`, so the job does not go green until the artifacts are live on
Central. Flipping the pair to `false`/`validated` rehearses an upload instead: the run
stops at a validated deployment awaiting a manual publish in the Portal.

The sources and javadoc jars are *not* release-only. Both are attached on every build of
the published modules, and javadoc runs with `doclint` at `all,-missing` and fails on an
error, so a broken `@link` surfaces at `mvn package` rather than after `release:prepare`
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
`-Darguments="-DskipTests -Djacoco.skip=true"`. That tree passed `verify` in
`preparationGoals`, and the job's own build verified the same sources beforehand, so a
third run would only repeat the Playwright suite. The static-analysis gates, both
dependency checks and the configuration-metadata check still run.

The changelog reaches the tagged commit because that commit is a plain `git commit` over
the whole index: maven-scm stages the POMs and then commits everything staged. So the
workflow renders and stages `CHANGELOG.md` before prepare runs, and
`checkModificationExcludes` stops the modification check refusing the staged file. No
version of maven-scm documents that, so `verify-release-commit` greps the new tag and
fails the job while the only damage is a tag nobody has consumed.

Reproducibility depends on `project.build.outputTimestamp` being pinned in the root pom and
in the testing-app's, and on every plugin version being explicit. That includes the
lifecycle plugins Maven would otherwise bind on its own. Clean, resources, install and
deploy sit at the versions `spring-boot-dependencies` manages, so the testing-app runs the
same ones. The site plugin, which Boot does not manage, was pinned at Maven 3.9.16's own
binding of 3.12.1; Dependabot has since moved it past. Surefire, failsafe, the compiler
and the dependency plugin have likewise moved past Boot's pins; the testing-app pins those
four in its own `pluginManagement`, and Dependabot bumps both poms in one pull request.

### Release notes

Everything lives in `.github/release-notes/`. `cliff.toml` classifies commits by
conventional-commit type into numbered groups and holds the markdown template, `site.jq`
reshapes git-cliff's `--context` JSON into the website's data file,
`check-commit-subjects.sh` is the push gate, and `test/` holds a fixture repo with golden
files.

`render.sh` is the only place git-cliff is invoked, and it refuses to run without the
config. That guard earns its keep: `git-cliff --config <missing>` merely warns, falls back
to its own built-in grouping and still exits 0, so a mistyped path would publish wrongly
grouped notes with nothing failing.

Commits sharing a subject collapse to one entry even when their bodies differ, because in
conventional mode git-cliff's `commit.message` holds only the description. Merge commits and
the `[release]` commits drop out via `filter_unconventional`, neither being conventional.
`filter_commits` is on with no `.*` catch-all, so an unrecognised type is dropped rather than
bucketed — which is what the gate is for.

The gate rejects any non-merge subject in the pushed range that is not a conventional commit.
This is not only about the notes: git-cliff derives the release version from these subjects
too, so one it cannot parse is a change it cannot weigh. It does not enforce the
50-character subject limit, because Dependabot's own `build(deps): bump ...` lines routinely
exceed it.

Two things in `cliff.toml` are load-bearing. `^build\(deps` must stay above the generic
`^build`, or every Dependabot commit lands in "Build, CI and chores". And group numbers must
stay zero-padded to two digits, because git-cliff sorts group names as strings — `<!-- 10 -->`
would sort ahead of `<!-- 2 -->`. `site.jq` hardcodes groups `01`-`05` as the user-facing set
the website shows, so a tenth group means editing both files.

The tests need git-cliff on `PATH`; the version and its sha512 are pinned in
`.github/actions/install-git-cliff`.

```bash
.github/release-notes/test/run.sh
```

Its expectations are golden files rendered from a fixture repo with fixed commit dates, so
they do not drift as `dev` grows. The fixture carries both spellings of a breaking change, a
subject repeated with a different body, a non-conventional subject, and two tags cut in the
same second.

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
- `mvn verify -DskipTests` fails at the coverage guard, by design; there is no data to
  gate on. Use `-Djacoco.skip=true` alongside it.
- `git-commit-id-maven-plugin` is declared only in `peekaboot-testing-app`, the one runnable
  application, and nowhere in the parent. `git.properties` lands at the classpath root and
  Spring resolves `classpath:git.properties` to a single resource, so a library shipping one
  can beat the host application's own file and make the dashboard report Peekaboot's branch
  as the app's. The testing-app pins the version itself with `failOnNoGitDirectory=false`,
  so a worktree whose gitdir pointer does not resolve, or an exported source tree, builds
  fine everywhere.
- The empty `peekaboot-spring-boot-starter` jar is intentional, and so are the empty
  `-sources`/`-javadoc` jars of the starter and the frontend. Do not "fix" the warnings.
