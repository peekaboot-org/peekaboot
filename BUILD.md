# Building Peekaboot

Plain Maven, one reactor, five static-analysis gates at `verify`. No wrapper, no Node
toolchain, no codegen beyond annotation processing.

## Prerequisites

| | |
| --- | --- |
| JDK | **25** — `maven.compiler.release=25`, no toolchains, no fallback |
| Maven | 3.9+ (verified on 3.9.9). No `mvnw` is checked in |
| Docker | Only for running the sample app and for `ScreenshotCapture`. **Not** needed by `mvn verify` |
| Network | First run only, to let Playwright download Chromium into `~/.cache/ms-playwright` |

## Commands

```bash
mvn clean verify     # compile + all tests + all five gates          <- the real build
mvn clean install    # the same, plus install into ~/.m2
mvn test             # tests only; see "What each command actually checks" below
mvn spotless:apply   # format (local builds already do this for you)

mvn -pl <module> test -Dtest=<Class>              # one class — never add -am
mvn -pl peekaboot-testing-app spring-boot:run     # sample app on :8083; needs Docker
                                                  # and an `mvn install` beforehand
```

### What each command actually checks

Only Error Prone runs during compilation; the other four gates are bound to `verify`.
So `mvn test` gives you Error Prone and nothing else, `mvn install`/`mvn verify` give
you all five. Per module, `verify` runs:

```
tests → package → sources jar → spotless:check → spotbugs:check → checkstyle:check → pmd:check
```

Gates run *after* the tests, so a failing test hides every gate failure behind it.
`peekaboot-testing-app` runs the same four in the reverse order — within a phase, Maven
follows POM declaration order, and that module declares them itself (see below). It also
adds `spring-boot:repackage`, so it is the only module producing an executable jar.

A cold `mvn clean verify` takes roughly 8 minutes on a warm local repository; the Playwright
suite is the bulk of it.

## The reactor

| Module | Artifact | Published | Contains |
| --- | --- | --- | --- |
| `peekaboot-parent` | pom | yes | All shared build config, dependency management (`spring-boot-dependencies` 4.1.1) |
| `peekaboot-backend` | jar | yes | Controllers, services, trace store, lifecycle listeners. Web/servlet/logback/Hikari/OTel deps are `<optional>` — the host app supplies them, auto-configuration conditions guard their use |
| `peekaboot-frontend` | jar | yes | `src/main/resources/static/peekaboot/ui/**` only. **No build step** — plain ES modules and CSS, copied as-is, no test sources |
| `peekaboot-spring-boot-autoconfigure` | jar | yes | Auto-configuration + `spring-boot-configuration-processor` metadata |
| `peekaboot-spring-boot-starter` | jar | yes | Dependency aggregator with no sources — Maven logs `JAR will be empty`, which is correct |
| `peekaboot-testing-app` | jar (boot) | **no** (`maven.deploy.skip`) | Sample app + the Playwright UI suite. See its [README](peekaboot-testing-app/README.md) |

`peekaboot-testing-app` deliberately parents to `spring-boot-starter-parent`, not to
`peekaboot-parent`, so it consumes the starter exactly as a real user would. The cost is
duplication: its POM re-declares the four verify-bound gates, the `spotless-apply-local`
profile and the Error Prone compiler config by hand, and it picks up Spring Boot's plugin versions for
everything else (`clean:3.5.0`, `resources:3.5.0`, `dependency:3.10.0` versus the parent's
pins). **Any change to the parent's build config has to be mirrored there.**

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
| Formatting | `spotless-maven-plugin` 3.10.0 (palantir-java-format 2.97.0) | inline in the POM | Java, ratcheted (below) |
| Bug patterns, compile-time | `error_prone_core` 2.50.0 via the compiler plugin | defaults | main + test |
| Bug patterns, bytecode | `spotbugs-maven-plugin` 4.10.4.0 | `config/spotbugs-exclude.xml` | main classes |
| Complexity metrics | `maven-checkstyle-plugin` 3.6.0 (checkstyle 14.0.0) | `config/checkstyle.xml` | main only |
| Code smells | `maven-pmd-plugin` 3.28.0 (PMD 7.26.0) | `config/pmd-ruleset.xml` | main Java |

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
  Mockito's inline mock-maker self-attaching and warning about it.
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
the ratchet, `~/.cache/ms-playwright` cached on a `**/pom.xml` hash, then `mvn --batch-mode
--update-snapshots clean verify`. The Chromium install is split into two steps on purpose:
`exec:java` ignores `-pl` scoping when combined with `-am`, so the reactor's SNAPSHOTs are
installed first (`-pl peekaboot-testing-app -am install -Dmaven.test.skip=true`) and the
plain `exec:java` call resolves against the local repo afterwards.

**`build-release-on-main-push.yml`** — see Releasing. Untested in anger: `dev` is currently
the only branch on the remote, so no release has ever run.

**`dependabot-pr-auto-merge.yml`** — auto-approves and auto-merges Dependabot PRs targeting
`dev`. Dependabot watches Maven daily and GitHub Actions weekly.

Branch model: `dev` is the default and integration branch; `main` is the release trunk, and
pushing to it releases. Branch protection on `dev` is configured but not enforcing — see
[`docs/IMPROVEMENTS.md`](docs/IMPROVEMENTS.md) §1.2.

## Releasing

Everything release-specific sits in the `peekaboot-release` profile; a normal build never
signs or publishes anything. A push to `main` (whose message does not contain `[release]`,
which is how recursion is prevented) runs:

1. `mvn --batch-mode verify`
2. `mvn -P peekaboot-release release:prepare -DskipStaging=true`
3. `mvn -P peekaboot-release release:perform`
4. GitHub release notes from the new tag, then an auto-merge of `main` back into `dev`

The profile adds `maven-release-plugin` 3.3.1 with Basjes'
`conventional-commits-version-policy` — the next version is derived from the conventional-commit
messages since the last `x.y.z` tag, so **commit message discipline decides the version bump**.
Tags are bare `@{project.version}`; release commits are prefixed `[release]`. It also attaches a
javadoc jar (`doclint` off, `failOnError` false), GPG-signs with `raphael@peekaboot.org`, and
publishes through `central-publishing-maven-plugin` (`autoPublish`, waits until published). A
sources jar is attached on *every* build of the published modules, not just releases.

`prepare` and `perform` are deliberately separate, with `-DskipStaging=true` on `prepare`:
combined, the artifact would already be uploaded during `prepare` and the build would not be
reproducible. Reproducibility also depends on `project.build.outputTimestamp` being pinned in
the parent POM and on every plugin version being explicit.

Secrets consumed by the workflow: `OSSRH_USERNAME`, `OSSRH_TOKEN`, `OSSRH_GPG_SECRET_KEY`,
`OSSRH_GPG_SECRET_KEY_PASSWORD`.

## Things that will bite you

- Local builds reformat your sources mid-build. Expect a dirty tree; that is by design.
- `git-commit-id-maven-plugin` is *managed but not bound* in the parent. `git.properties`
  lands at the classpath root and Spring resolves `classpath:git.properties` to a single
  resource, so a library shipping one can beat the host application's own file and make the
  dashboard report Peekaboot's branch as the app's. Only `peekaboot-testing-app` — the one
  runnable application — declares it, and it re-pins version 10.0.0 with
  `failOnNoGitDirectory=false` because it does not inherit the parent's `pluginManagement`.
- A worktree whose gitdir pointer does not resolve, or an exported source tree, is fine
  everywhere thanks to that `failOnNoGitDirectory=false`.
- The empty `peekaboot-spring-boot-starter` jar is intentional. Do not "fix" the warning.
