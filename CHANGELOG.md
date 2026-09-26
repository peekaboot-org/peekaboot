## 0.3.0 - 2026-09-26

### Features
- Open span details from the span tree
- Mark span kinds with coloured dots
- Make span tree rows easier to scan
- Size the span name column to its names
- Show span errors in span details
- Copy a span id from span details
- Show or hide all span details at once
- Drop the counts the overlay tabs repeat
- Drop Content-Type from the Request section
- Count spans and logs in the trace stat line
- Show query row counts as neutral chips
- Add the error-page switch
- Render a Peekaboot error page
- Serve the error page in place of whitelabel
- Put the toolbar on error pages
- Add an error-page override switch
- Beat a ControllerAdvice's own error page
- Resolve the stack-trace exclusion list
- Decide which stack-trace frames fold away
- Add the stack-trace folding properties
- Fold framework frames on the error page
- Reveal the full trace on the error page
- Capture the stack trace of a logged error
- Show a logged error's trace, folded
- Classify async tasks as their own action
- Observe tasks handed to task executors
- Register the async task decorator
- Track async subtrees in the trace bundle
- Keep async work out of a trace's duration
- Keep async spans out of the span summary
- Map a trace subtree as its own tree
- List async subtrees as their own rows
- Link, distinguish and open async rows
- Fold async work out of a trace's timeline
- Remove a trace from Slow when it is not slow
- Give an async row its own logs and status
- Single-space the error page stack trace

### Bug fixes
- **security**: Tolerate other filter registrations
- Show span tag keys in full
- Group every count in the reader's locale
- Render buttons in the UI font
- Keep the jump highlight on a hovered row
- Move the testing app off isbn-scanner's port
- Capture a failed request as a server error
- Stop the dependabot merge waiting on itself
- **stack-trace**: Classify suppressed frames
- **stack-trace**: Merge adjacent application runs
- **error-page**: Ask FoldedTrace what to reveal

### Dependency upgrades
- **deps-dev**: Bump org.apache.maven.plugins:maven-deploy-plugin
- **deps-dev**: Bump org.apache.maven.plugins:maven-install-plugin
- **deps-dev**: Bump org.codehaus.mojo:exec-maven-plugin
- **deps**: Bump com.microsoft.playwright:playwright
- **deps**: Bump net.ttddyy.observation:datasource-micrometer-opentelemetry
- **deps**: Bump net.ttddyy.observation:datasource-micrometer-spring-boot
- **deps**: Use jdbc-url-parser 0.1.2

### Reverts
- Failed 0.3.0 release preparation

### Documentation
- Correct when preparationGoals run
- Describe the error page and its toolbar
- Describe stack-trace folding
- Fix ARCHITECTURE's frame-detection notes
- Describe how the Dependabot merge waits
- List the frontend linters among the gates
- Describe async task instrumentation
- Add the beta notice and name to the README
- Take over internals cut from the user docs
- Refresh the README dashboard screenshot

### Internal changes
- One stylesheet inliner for both surfaces
- Drop four unused catch bindings
- Replace the deprecated word-break value
- Drop a dead parameter from the trace header
- Declare the dashboard's buttons explicitly
- Delete three members nothing calls

### Tests
- Lose the script without expiring the route
- Cover the error page and its bar over HTTP
- Pin the error detail settings invariant
- Pin the reveal control's toggle-back path
- Capture the error page and an async trace

### Build, CI and chores
- Merge main back to dev by pushing
- Commit the changelog with the release version
- Put a space in the release tag message
- Ignore session notes and key exports
- Keep the local ignores out of the repo
- Rebase dependabot auto-merges
- Release from dev, fast-forward main
- Publish the site with the release
- Wait for the build instead of auto-merge
- Pass GH_REPO to the Dependabot merge
- Lint the frontend's JS, CSS and HTML
- Publish branch snapshots to Maven Central
- Take the snapshot version from a shared action
- Track the shared action by its major tag
- Drop the spotless ratchet
- Format the whole tree with spotless

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/0.2.0...0.3.0

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

