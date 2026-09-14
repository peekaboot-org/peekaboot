## 0.2.0 - 2026-09-14

### Features
- Expose whether trace spans can arrive
- **starter**: Capture JDBC queries out of the box
- Show a query's row count in the span tree
- Secure the dashboard automatically
- Bundle the Geist webfonts

### Bug fixes
- Close the defects the docs rewrite left
- Correct trace and span edge cases
- Harden the passthrough writer hand-over
- Close the remaining masking gaps
- Shut down cleanly behind a stalled SSE peer
- Correct activation and startup edge cases
- Correct dashboard UI defects
- Keep forwarded pages intact under Tomcat 11
- **tracing**: Scope observations to the dispatch, not the request
- **toolbar**: Stack the trace overlay above host page chrome
- Polish the dashboard UI
- Fail the release-data render on a broken pipe

### Performance
- Apply the audit's performance fixes
- Cache the bundled font for a year

### Dependency upgrades
- **deps**: Bump org.apache.maven.plugins:maven-failsafe-plugin
- **deps**: Bump io.github.git-commit-id:git-commit-id-maven-plugin
- **deps**: Bump org.apache.maven.plugins:maven-surefire-plugin
- **deps**: Bump com.diffplug.spotless:spotless-maven-plugin
- **deps**: Bump com.github.spotbugs:spotbugs-maven-plugin
- **deps**: Bump com.diffplug.spotless:spotless-plugin-gradle
- **deps**: Bump org.springdoc:springdoc-openapi-starter-webmvc-ui
- **deps**: Bump actions/setup-java in /.github/actions/prepare-build
- **deps**: Bump WyriHaximus/github-action-get-previous-tag

### Reverts
- Restore the URL query masking rule

### Documentation
- Point the quick start at the released 0.1.0
- **build**: Drop the post-release step that cannot recur
- Apply the writing-style pass
- Correct what the rewrite got wrong
- Rewrite the project documentation
- Align the docs with the audited code
- Describe the automatic dashboard security
- Fix site anchor for the SLOW badge caveat
- Cover the browser property and nightly run
- Document the bundled webfont
- Record the release-notes pipeline
- Say where the unreleased draft lives

### Internal changes
- Tidy up after the docs rewrite
- Apply the audit's structural cleanups
- Narrow security-internal API to package-private

### Tests
- **screenshots**: Scrub the capture machine off the Overview shot
- Keep foreign logs out of the trace store
- Keep the demo jobs off their timers
- Cover the docs-rewrite defects
- Strengthen the suite after the audit
- Stabilise the UI and log-capture tests
- Pin the container-closed writer path
- Cover the dashboard security guard
- Wait for the tab panel fade-in
- Keep the UI suite engine-agnostic
- Cover the bundled webfont
- Settle hover reads on transition end
- Wait for the dashboard's self-reload

### Build, CI and chores
- Apply the audit's build fixes
- Share the build preparation steps
- Add a nightly cross-browser workflow
- Generate grouped release notes
- Publish release data to the website
- Reduce a first release to one line

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/0.1.0...0.2.0

## 0.1.0 - 2026-09-04

Initial release.

