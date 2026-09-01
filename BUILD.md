# Building Peekaboot

Maven is the system of record: one reactor, six gates at `verify` - five static-analysis
and one coverage floor. No Node toolchain, no codegen beyond annotation processing.
A parallel **Gradle build** covers the same modules, tests and gates (see
[the Gradle build](#the-parallel-gradle-build) below); until it is proven equivalent in
CI, every Maven statement in this document is authoritative and the Gradle build must be
kept in lockstep.

Both builds ship wrapper scripts, each pinned by SHA-256 checksum: `./mvnw` (downloads
Maven 3.9.9, `.mvn/wrapper/maven-wrapper.properties`) and `./gradlew` (downloads Gradle
9.7.0, `gradle/wrapper/gradle-wrapper.properties`). A locally installed Maven 3.9+ works
exactly the same.

## Prerequisites

| | |
| --- | --- |
| JDK | **25** — `maven.compiler.release=25` / Gradle `options.release = 25`, no toolchains, no fallback |
| Maven / Gradle | via the checked-in wrappers (`./mvnw`, `./gradlew`); a local Maven 3.9+ (verified on 3.9.9) also works |
| Docker | Only for running the sample app and for `ScreenshotCapture`. **Not** needed by `mvn verify` |
| Network | First run only: the wrappers download their build tool, and Playwright downloads Chromium into `~/.cache/ms-playwright` |

## Commands

```bash
mvn clean verify     # compile + all tests + all six gates           <- the real build
mvn clean install    # the same, plus install into ~/.m2
mvn test             # the fast gate: Error Prone + unit tests only (~1 min);
                     # integration tests (*IT) don't run before `verify`
mvn spotless:apply   # format (local builds already do this for you)

mvn -pl <module> test -Dtest=<Class>              # one unit-test class — never add -am
mvn -pl <module> verify -Dit.test=<Class>         # one *IT class (runs this module's
                                                  # gates too; that's inherent to verify)
mvn -pl <module> -am verify -Dit.test=<Class> \
    -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dfailsafe.failIfNoSpecifiedTests=false           # the same against uncommitted sibling-module
                                                  # changes: both skip flags, or failsafe fails the
                                                  # first upstream module with "No tests matching pattern"
mvn -pl peekaboot-testing-app spring-boot:run     # sample app on :8083; needs Docker
                                                  # and an `mvn install` beforehand
```

### What each command actually checks

Only Error Prone runs during compilation; the other five gates are bound to `verify`.
Tests are split by lifecycle: plain unit tests live in `*Test` classes and run at `test`
(surefire), while anything that boots a real application — every `@SpringBootTest`, the
whole Playwright suite — lives in `*IT` classes and runs at `integration-test`
(failsafe). So `mvn test` is the fast gate — Error Prone plus every unit test, nothing
else — and `mvn install`/`mvn verify` give you all six gates plus the integration
tests. Per module, `verify` runs:

```
unit tests → package → sources jar → javadoc jar → integration tests (*IT) → spotless:check → spotbugs:check → checkstyle:check → pmd:check
```

`peekaboot-testing-app` runs its `*IT` classes **concurrently inside one JVM** — 2
worker threads (`-Dpeekaboot.it.threads=N`; `1` serializes when diagnosing a flaky
test), each owning its own Chromium, all sharing one Spring context cache and therefore
one running app per context configuration. That concurrency is deliberate beyond speed:
concurrent test classes hammer peekaboot the way a real concurrent host application
does, so a race in peekaboot itself shows up here first. Classes that genuinely cannot
overlap coordinate through JUnit `@ResourceLock` (see `DashboardTraceViewIT`).
`-Dpeekaboot.it.forks=N` still exists on top (forks × threads both apply) but defaults
to 1. The coverage gate sees the same `jacoco.exec` data it would from a serial run.

`peekaboot-coverage` runs last and adds the sixth gate over the whole reactor:

```
jacoco:merge -> enforcer (coverage data present?) -> jacoco:check
```

Gates run *after* the tests, so a failing unit test hides every gate failure behind it.
(A failing *IT* is reported by `failsafe:verify`, also bound to `verify` — in modules
that inherit the gates from the parent it lands after them, so there the gates still run.)
`peekaboot-testing-app` runs the same four in the reverse order — within a phase, Maven
follows POM declaration order, and that module declares them itself (see below). It also
adds `spring-boot:repackage`, so it is the only module producing an executable jar.

A cold `mvn clean verify` takes roughly 3-5 minutes on a warm local repository (the
Playwright suite is the bulk of it, concurrency notwithstanding); `mvn test` alone
stays around a minute.

## The reactor

| Module | Artifact | Published | Contains |
| --- | --- | --- | --- |
| `peekaboot-parent` | pom | yes | All shared build config, dependency management (`spring-boot-dependencies` 4.1.1) |
| `peekaboot-backend` | jar | yes | Controllers, services, trace store, lifecycle listeners. Web/servlet/logback/Hikari/OTel deps are `<optional>` — the host app supplies them, auto-configuration conditions guard their use |
| `peekaboot-frontend` | jar | yes | `src/main/resources/static/peekaboot/ui/**` only. **No build step** — plain ES modules and CSS, copied as-is, no test sources. Its `-javadoc` jar is empty on purpose (below) |
| `peekaboot-spring-boot-autoconfigure` | jar | yes | Auto-configuration + `spring-boot-configuration-processor` metadata |
| `peekaboot-spring-boot-starter` | jar | yes | Dependency aggregator with no sources — Maven logs `JAR will be empty`, which is correct. Its `-sources` and `-javadoc` jars are empty on purpose (below) |
| `peekaboot-testing-app` | jar (boot) | **no** (`maven.deploy.skip`, see [Releasing](#releasing)) | Sample app + the Playwright UI suite. See its [README](peekaboot-testing-app/README.md) |
| `peekaboot-coverage` | pom | **no** (`skipPublishing`, see [Releasing](#releasing)) | No sources. Merges every module's coverage data, renders the aggregate report and enforces the floor. Builds last |

Maven Central requires a `-sources` and a `-javadoc` jar for every jar component, and two of
the published modules have no Java sources: `maven-source-plugin` would build nothing for the
starter, and `javadoc:jar` builds nothing for the starter or the frontend. So the starter sets
`maven.source.forceCreation`, and both modules package their empty `target/apidocs` as the
`-javadoc` jar through an extra `maven-jar-plugin` execution. Empty is the intended content.

`peekaboot-testing-app` deliberately parents to `spring-boot-starter-parent`, not to
`peekaboot-parent`, so it consumes the starter exactly as a real user would. The cost is
duplication: its POM re-declares the four verify-bound static-analysis gates, the JaCoCo
agent wiring, the `spotless-apply-local` profile and the Error Prone compiler config by
hand, and it picks up Spring Boot's plugin versions for everything else rather than the
parent's pins. **Any change to the parent's build config has to be mirrored there.**

## The parallel Gradle build

`settings.gradle.kts` mirrors the reactor module for module. `./gradlew build` is the
`mvn clean verify` equivalent: unit tests (`test`, `*Test` only), integration tests
(`integrationTest`, `*IT`, concurrent classes exactly like failsafe —
`peekaboot.it.threads` in `gradle.properties`), all five static-analysis gates at the
same tool versions reading the same `config/` files, and the reactor-wide coverage gate
(`:peekaboot-coverage:coverageGate`, same 90%/75% floors on merged execution data).
`./gradlew test` is the fast gate, `./gradlew assemble` just builds the jars.

Structure: `buildSrc/src/main/kotlin/peekaboot.java-conventions.gradle.kts` plays the
role of `peekaboot-parent` (shared compiler, gate, JaCoCo, and test-split config); each
module's `build.gradle.kts` declares only its dependencies. `peekaboot-testing-app`
applies the same convention plus the Spring Boot and git-properties plugins — the
consume-the-starter-as-a-published-artifact proof stays with Maven, which is why the
Maven module keeps its `spring-boot-starter-parent` parent.

**Lockstep.** Two values live once, in `gradle.properties`: `version` (the six poms'
`<version>` — `maven-release-plugin` rewrites only the poms, so **bump it by hand after every
release**, see the checklist under [Releasing](#releasing), or `./gradlew build` keeps
producing pre-release jars) and `springBootVersion` (the root pom's `spring-boot.version` and the testing-app's
parent version), which the convention plugin turns into the BOM import every module gets
and `buildSrc` reads for the Boot plugin. Mockito's agent jar takes its version from that
BOM, as Maven does. Every other shared literal — Error Prone, palantir, the ratchet SHA,
Checkstyle, SpotBugs, JaCoCo, PMD, the coverage floors, the build timestamp, Playwright,
springdoc and the testing-app's direct dependencies — is written on both sides and has to
change on both. Dependabot watches the `gradle` ecosystem next to `maven`, so version bumps
arrive as paired PRs.

**Reproducibility and artifact parity** (verified by building each system twice and
cross-diffing): both builds are self-reproducible - byte-identical jars across
rebuilds - via `project.build.outputTimestamp` (Maven) and
`preserveFileTimestamps=false` + `reproducibleFileOrder=true` (Gradle), with the
testing-app's `build-info.properties`/`git.properties` build times pinned to the same
instant on both sides. Across systems, every class file and resource in the published
jars is byte-identical; the remaining, expected differences are `META-INF/MANIFEST.MF`
(Maven adds `Created-By`/`Build-Jdk-Spec` lines) and Maven's `META-INF/maven/**`
metadata. The testing-app boot jar additionally differs in dependency resolution:
Maven's nearest-wins picks Jackson 2.21.x for springdoc's transitives where Gradle's
highest-wins picks 2.22.0 (and includes `aopalliance-1.0`); acceptable for the
unpublished sample app, but it is the first thing to reconcile if the Gradle build is
ever promoted.

Not ported (deliberately, local-first): the `peekaboot-release` profile, publishing,
and CI wiring - CI still runs Maven only.

## Compilation

- `release 25`; `peekaboot-testing-app` additionally compiles with `-parameters`.
- `<proc>full</proc>` — JDK 23+ no longer runs classpath annotation processors implicitly,
  and `spring-boot-configuration-processor` has to keep running.
- `annotationProcessorPaths` replaces classpath scanning entirely, so both processors are
  listed explicitly: `error_prone_core` 2.50.0 and `spring-boot-configuration-processor`.
- `.mvn/jvm.config` carries the `--add-exports`/`--add-opens` into `jdk.compiler` that
  Error Prone needs since JDK 16 sealed those packages. They apply to the *Maven* JVM
  because javac is not forked. Deleting that file breaks every compile.

## Quality gates

| Gate | Plugin (tool version) | Config | Scope |
| --- | --- | --- | --- |
| Formatting | `spotless-maven-plugin` 3.10.1 (palantir-java-format 2.97.0) | inline in the POM | Java, ratcheted (below) |
| Bug patterns, compile-time | `error_prone_core` 2.50.0 via the compiler plugin | defaults | main + test |
| Bug patterns, bytecode | `spotbugs-maven-plugin` 4.10.4.0 | `config/spotbugs-exclude.xml` | main classes |
| Complexity metrics | `maven-checkstyle-plugin` 3.6.0 (checkstyle 14.0.0) | `config/checkstyle.xml` | main only |
| Code smells | `maven-pmd-plugin` 3.28.0 (PMD 7.27.0) | `config/pmd-ruleset.xml` | main Java |
| Coverage floor | `jacoco-maven-plugin` 0.8.15 | inline in `peekaboot-coverage/pom.xml` | all published classes, reactor-wide |

Each config file explains its own exclusions; the short version:

- **Checkstyle is metrics-only** (cyclomatic/NPath/boolean complexity, NCSS, method length,
  parameter count, fan-out) at stock thresholds. Formatting belongs to Spotless and bug
  patterns to Error Prone/SpotBugs — none of it is duplicated. Test sources are excluded:
  test data builders legitimately mirror wide domain records.
- **PMD** is `rulesets/java/quickstart.xml` minus `AvoidUsingVolatile` (SpotBugs'
  `AT_STALE_THREAD_WRITE_OF_PRIMITIVE` demands exactly that modifier, so the two tools
  would deadlock) and `GuardLogStatement` (parameterized SLF4J calls are the project style).
- **SpotBugs** excludes `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` globally: the DTOs are JSON
  carriers for actuator payloads, and `List.copyOf`/`Map.copyOf` would throw on the nulls
  real actuator data contains.
- **Nothing lints the frontend's JS or CSS.** PMD's `pmd-javascript` module is not an
  option: its Rhino parser throws `NullPointerException` on destructuring, which the
  frontend uses throughout (`function formatDateTime(value, {locale, timeZone, ...options}
  = {})`), and it fails outright on four files. A real JS linter means ESLint and therefore
  a Node toolchain, which this build deliberately does not have — an open decision, not an
  oversight.

Config paths resolve through `${maven.multiModuleProjectDirectory}`, which Maven sets to the
directory holding `.mvn/`. That makes them work from the repo root and from inside a module
directory alike.

### The coverage gate

`peekaboot-coverage` holds it: line >= 90%, branch >= 75% over every published class,
measured on the merged data of the whole reactor. At the time the gate landed the real
figures were **95.6% line, 81.8% branch** (97.3% of methods), so the floors sit well below
today's value: they catch a substantial regression, not a few uncovered lines. Both are
properties - `jacoco.min.line` and `jacoco.min.branch` - so raising the floor is a one-line
commit. Lowering one to make a build pass is not a fix.

Three things about that module are deliberate and worth knowing before changing it:

- **It exists because `jacoco:check` only ever analyses the classes of the module it runs
  in.** There is no `check-aggregate` goal. So the gated classes are physically collected
  here: `maven-dependency-plugin:unpack-dependencies` unpacks the published jars into
  `target/classes`, and `check` runs against those. JaCoCo matches execution data to
  classes by a hash of the bytecode, so the unpacked copies are the same classes the tests
  ran.
- **It depends on every measured module, including the sample app.** That is what forces it
  to build last, after every `jacoco.exec` has been written. `peekaboot-testing-app`'s own
  code is excluded from both the report and the gate, but its tests run peekaboot in-process,
  so its execution data carries about five points of backend coverage: measured on its own
  tests alone, `peekaboot-backend` covers 90.3% of lines; merged, 95.6%. Per-module
  reporting would throw that away.
- **A missing data file makes `jacoco:check` pass silently**, which would turn the gate into
  decoration the moment the merge broke. `maven-enforcer-plugin` fails the build first if
  `target/jacoco-merged.exec` is absent. The consequence is that `mvn verify -DskipTests`
  now fails on purpose; pass `-Djacoco.skip=true` to turn the agent, the check and the guard
  off together.

The aggregate HTML report lands at `peekaboot-coverage/target/site/jacoco-aggregate/index.html`,
with per-module drill-down. Nothing publishes it.

### The Spotless ratchet

`ratchetFrom` is pinned to commit `b1a96cc` (where formatting was introduced). Only files
whose content differs from that commit are formatted and checked; untouched legacy files are
left alone. Two consequences:

- **A shallow clone breaks the build** — the ratchet has to resolve that commit. CI uses
  `fetch-depth: 0` for exactly this.
- **Local builds rewrite your working tree.** The `spotless-apply-local` profile is active
  whenever `env.CI` is unset and runs `spotless:apply` at `process-sources`, so
  `spotless:check` can never surprise you at `verify`. On CI (`CI=true` on GitHub Actions)
  the profile is off and unformatted code fails the build instead of being silently fixed
  inside a sandbox that never pushes the result back. Set `CI=1` locally to reproduce that.

## Tests

Surefire 3.5.6, JUnit 5 + AssertJ. Conventions, the pristine-output policy and the known
flakes live in [`docs/TESTING.md`](docs/TESTING.md) — this section covers only the build
mechanics.

- Test sources exist in `peekaboot-backend`, `peekaboot-spring-boot-autoconfigure` and
  `peekaboot-testing-app`. Those three resolve `${org.mockito:mockito-core:jar}` via
  `maven-dependency-plugin:properties` and pass it to Surefire as `-javaagent`, which avoids
  Mockito's inline mock-maker self-attaching and warning about it. Their `argLine` starts
  with `@{jacocoArgLine}` so the coverage agent survives alongside it - see below.
- `peekaboot-testing-app`'s tests activate the `test` profile: H2 instead of PostgreSQL,
  Docker Compose off. `mvn verify` therefore needs neither Docker nor a database.
- Its Playwright tests drive real headless Chromium. The driver downloads it on first use;
  install it explicitly with the `exec:java` invocation in the module README if that fails.
- Two classes are excluded from normal runs by *naming*, not configuration:
  `ScreenshotCapture` (a website-screenshot tool that does need Docker) and
  `TraceWritePathBenchmark`. Neither matches Surefire's default `*Test` includes.
- Never combine `-am` with `-Dtest`.

## CI

Three workflows, all under `.github/workflows/`.

**`build-on-push.yml`** — every branch except `main`. JDK 25 (temurin), `fetch-depth: 0` for
the ratchet, `~/.cache/ms-playwright` cached under a key derived from the testing-app's
`playwright.version` property (Chromium changes with Playwright, not with any other
dependency), then `./mvnw --batch-mode clean verify`. The Chromium install is split into two
steps on purpose: `exec:java` ignores `-pl` scoping when combined with `-am`, so the
reactor's SNAPSHOTs are installed first (`-pl peekaboot-testing-app -am install
-Dmaven.test.skip=true`) and the plain `exec:java` call resolves against the local repo
afterwards. That ad-hoc call is why the testing-app pom pins `exec-maven-plugin` in
`pluginManagement`: `spring-boot-starter-parent` does not manage it, and an unpinned prefix
invocation resolves whatever is latest that day.

Both workflows build with the checked-in `./mvnw`, and every action is pinned to a commit
SHA with the tag in a trailing comment; Dependabot's `github-actions` updates move the pins.

**`build-release-on-main-push.yml`** — see Releasing. Untested in anger: `dev` is currently
the only branch on the remote, so no release has ever run.

**`dependabot-pr-auto-merge.yml`** — auto-approves and auto-merges Dependabot PRs targeting
`dev`. Dependabot watches Maven and Gradle daily and GitHub Actions weekly.

Branch model: `dev` is the default and integration branch; `main` is the release trunk, and
pushing to it releases. Branch protection on `dev` is configured but not enforcing — see
[`docs/IMPROVEMENTS.md`](docs/IMPROVEMENTS.md) §1.2.

## Releasing

Everything release-specific sits in the `peekaboot-release` profile; a normal build never
signs or publishes anything. A push to `main` (whose message does not contain `[release]`,
which is how recursion is prevented) runs:

1. `./mvnw --batch-mode verify`
2. `./mvnw -P peekaboot-release release:prepare`
3. `./mvnw -P peekaboot-release release:perform`
4. GitHub release notes from the new tag, then a pull request `main` → `dev` with auto-merge
   enabled (`gh pr create` + `gh pr merge --auto`), carrying the two `[release]` version
   commits back. It is a PR and not a push because the `green-default-branch` ruleset on
   `dev` requires the `build-on-push` status check and only admins may bypass it; a merge
   commit pushed by `GITHUB_TOKEN` has no such check and is refused. Note the PR itself does
   not get that check either — `build-on-push` ignores `main`, and events raised with
   `GITHUB_TOKEN` trigger no workflows — so until the job runs with a token that does, the
   PR waits for a human merge (an open one is reused by the next release)

Nothing automates what follows a release; do it on `dev` once the merge-back has landed:

1. Set `version` in `gradle.properties` to the new `-SNAPSHOT` version the poms now carry.
2. In the docs site (`../peekaboot-org.github.io`), set `peekaboot_version` in `_config.yml`
   to the released version — every dependency snippet on the site reads it.
3. Remove the pre-release callout from the site's `quick-start.md`.
4. Put the released version into the two quick-start snippets in `README.md`.

The profile adds `maven-release-plugin` 3.3.1 with Basjes'
`conventional-commits-version-policy` — the next version is derived from the conventional-commit
messages since the last `x.y.z` tag, so **commit message discipline decides the version bump**.
Tags are bare `@{project.version}`; release commits are prefixed `[release]`. It also
GPG-signs with `raphael@peekaboot.org` and publishes through
`central-publishing-maven-plugin` (`autoPublish`, waits until published). The sources and
javadoc jars are *not* release-only: both are attached on every build of the published
modules, and javadoc runs with `doclint` at `all,-missing` and fails the build on an error, so
a broken `@link` surfaces at `mvn package` rather than after `release:prepare` has pushed the
tag.

Two modules stay out of the bundle, by two different switches. The publishing plugin binds
itself to `deploy` in every module that inherits the profile and **ignores
`maven.deploy.skip`**; `peekaboot-coverage` therefore opts out with `skipPublishing`, the
plugin's per-module switch (it only filters that module's own artifacts — the bundle is still
uploaded from there, the last module of the reactor). `peekaboot-testing-app` never sees the
plugin at all: the profile is undefined in its `spring-boot-starter-parent` pom, so the plain
`maven-deploy-plugin` runs for it, and `maven.deploy.skip` is what keeps the sample app out.

`release:prepare` bumps the POMs to the release version, commits, tags, runs its
`preparationGoals` (`clean verify`) against that tag and then commits the next
`-SNAPSHOT` version; it deploys nothing. `release:perform` checks the tag out into
`target/checkout` and runs the configured `<goals>` (`deploy`) there, which is where signing
and the upload to Maven Central happen. The workflow passes it
`-Darguments="-DskipTests -Djacoco.skip=true"`: that tree has passed `verify` twice by then
(the job's own build, then `preparationGoals`), so the third run would only repeat the
Playwright suite. The four static-analysis gates still run. Reproducibility depends on
`project.build.outputTimestamp` being pinned in the parent POM and on every plugin version
being explicit.

### First release

`ConventionalCommitsVersionPolicy` looks at the commits since the most recent tag matching
`x.y.z`, takes the highest step it finds (`feat:` → minor; `type!:` or a `BREAKING CHANGE:`
line anywhere in the message → major, `-SNAPSHOT` stripped otherwise) and applies it to that
tag's version. With **no tag** it walks the entire history and starts from the pom version
instead. There is no tag today, and commit `254956ae` carries a `BREAKING CHANGE:` footer,
so an unprepared first release would be **1.0.0** — verified with
`./mvnw -P peekaboot-release release:prepare -DdryRun=true`, which reports
`Starting from project.version 0.0.5-SNAPSHOT … Doing a MAJOR version increase … Next
release version : 1.0.0`.

The first release is **0.1.0**. The recipe that works with the workflow exactly as it is:

1. On `dev`, tag the commit that bumped the poms to `0.0.5-SNAPSHOT` as the baseline, and
   push the tag: `git tag 0.0.4 ef175ff && git push origin 0.0.4`. Everything after it is
   `feat:`/`fix:`/`chore:`/`test:` work without a breaking marker, so the policy computes a
   minor step from 0.0.4 (verified in a throwaway clone: `Starting from SCM tag with version
   0.0.4 … Doing a MINOR version increase … Next release version : 0.1.0`). The tag must sit
   *before* the features that make this a minor release: on the current tip the window holds
   no `feat:` and the same dry run answers `0.0.5`.
2. Confirm nothing merged after the tag carries `!:` or a `BREAKING CHANGE:` footer — either
   turns the answer into 1.0.0.
3. Create `main` from `dev` and push. The workflow tags `0.1.0`; the next development version
   is `0.1.1-SNAPSHOT`.

The manual alternative, `./mvnw --batch-mode -P peekaboot-release release:prepare
-DreleaseVersion=0.1.0` (dry run verified: tag `0.1.0`, next `0.1.1-SNAPSHOT`), bypasses the
policy for that one run. A push-triggered workflow cannot carry that flag, so it is the
fallback for a release run by hand, not the plan.

Secrets consumed by the workflow: `OSSRH_USERNAME`, `OSSRH_TOKEN`, `OSSRH_GPG_SECRET_KEY`,
`OSSRH_GPG_SECRET_KEY_PASSWORD`. The `OSSRH_` prefix is historical — the workflow talks to
the Central Portal (`server-id: central`) and the first two hold a Portal user token, not
OSSRH credentials. The names live in the repository settings as well as in the workflow, so a
rename has to touch both.

## Things that will bite you

- Local builds reformat your sources mid-build. Expect a dirty tree; that is by design.
- `mvn verify -DskipTests` fails at the coverage guard, by design - there is no data to
  gate on. Use `-Djacoco.skip=true` alongside it.
- `git-commit-id-maven-plugin` is *managed but not bound* in the parent. `git.properties`
  lands at the classpath root and Spring resolves `classpath:git.properties` to a single
  resource, so a library shipping one can beat the host application's own file and make the
  dashboard report Peekaboot's branch as the app's. Only `peekaboot-testing-app` — the one
  runnable application — declares it, and it re-pins version 10.0.0 with
  `failOnNoGitDirectory=false` because it does not inherit the parent's `pluginManagement`.
- A worktree whose gitdir pointer does not resolve, or an exported source tree, is fine
  everywhere thanks to that `failOnNoGitDirectory=false`.
- The empty `peekaboot-spring-boot-starter` jar is intentional, and so are the empty
  `-sources`/`-javadoc` jars of the starter and the frontend. Do not "fix" the warnings.
