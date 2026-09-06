# peekaboot-test-support

Shared test helpers for the sibling modules' test suites. It builds with the rest of the
reactor and is never published, so nothing an application depends on comes from here.

It holds one class today, `LogCapture` (`org.peekaboot.testsupport`): the logback
`ListAppender` wrapper that `peekaboot-backend` and `peekaboot-spring-boot-autoconfigure`
tests use to assert on deliberate log events instead of letting them reach the console (see
[`docs/TESTING.md`](../docs/TESTING.md)).

## Why it is a module of its own

`LogCapture` used to live in `peekaboot-backend`'s test tree and reach the other modules as
an attached `-tests` jar (Maven) or test fixtures (Gradle). That collides with publishing
to Maven Central:

- `central-publishing-maven-plugin`'s `excludeArtifacts` matches artifactId only
  (bytecode-verified), so a `-tests` classifier of a published artifactId cannot be kept
  out of the Central bundle.
- Unbinding the test-jar execution under the release profile breaks the release reactor
  instead. The consuming module's `<type>test-jar</type>` dependency then fails to resolve
  under `deploy -DskipTests`.

A separate, never-published module has neither problem. The whole artifact stays out of the
bundle via `skipPublishing`, the per-module switch of `central-publishing-maven-plugin`
that `peekaboot-coverage` also uses, and the sibling modules consume it as a plain
test-scope dependency that always resolves in the reactor.

## Rules

- **Test scope only.** Sibling modules may depend on this module exclusively with
  `<scope>test</scope>` / `testImplementation`; it must never leak into a published compile
  or runtime classpath.
- **Never published.** No release step uploads this jar. Keep `skipPublishing` set.
- **No dependency on the sibling modules.** Only logback and slf4j. The backend's own
  fixture builders (`Spans`, `SpanNodes`, `RequestCompletedEvents`, `TraceStores`)
  construct backend domain types and therefore stay in `peekaboot-backend`'s test tree.
  Moving them here would create a dependency cycle.
