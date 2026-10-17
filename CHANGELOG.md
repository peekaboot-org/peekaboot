## 1.1.0 - 2026-10-17

### Features
- Label queries with their DataSource name
- Map Liquibase change sets into insights
- Read Liquibase through its actuator endpoint
- Add the Liquibase dashboard tab
- Trace event listener invocations
- Fold Peekaboot's own frames by default
- Warn when an insights series never resolves
- Warn about the error page on a deployment
- Read a blank switch value as unset
- Warn only about an error page that is served
- Rename the pool trace type to maintenance
- Classify redis upkeep traces as maintenance

### Bug fixes
- Render span toggle buttons in the bundled font
- List non-default-candidate DataSources
- Show Flyway for non-default-candidate beans
- Serve the error page only to clients asking for HTML
- Fail the async task span when @Async throws
- Stop startup on a non-boolean switch value
- Record submitted task failures on async spans
- List a late async row at the top of the list
- Warn on every subtract-meter outage
- Drop the ERROR span issue nothing renders
- Ignore an empty manifest Class-Path
- Open a trace from anywhere on its row
- Keep timeline tick labels distinct
- Keep info apart from pink for protanopes
- Explain why the Traces tab cannot fill
- Keep the overlay's Escape inside the overlay
- Let the keyboard scroll overlay tab content
- Stop warning when shutdown cuts a write short
- Challenge every dashboard request
- Write each insights tick under one lock
- Open the subtree hiding a cross-link target
- Keep span toggle focus rings inside the row
- Name the pool checkbox by what it does
- Keep toggle labels fixed and state in ARIA
- Keep truncated traces in the Slow bucket
- Keep async descendants out of the sync window
- Keep Spring's status on the override page
- Log the exception the override page renders
- Guard the dashboard under the servlet path
- Guard requests forwarded into the dashboard
- Drop the WARN on a dashboard challenge
- Fold stack traces by default on every launch
- Mark a truncated parameter batch
- Read every roll-up window under one lock
- Never write a capture taken before restore
- Show the error when the recovery reload stalls
- Put the servlet path in the banner URLs
- Name the guarded path in the security posture
- Honour spring.mvc.log-resolved-exception
- Escape the base path in page URLs
- Read listing roots under one lock
- Count one omitted character or byte singular
- Count one omitted stack-trace line singular
- Keep insights time labels from overlapping
- Keep insights deltas that race a snapshot

### Performance
- Build span panels when first opened
- Map only the async subtree for its row
- Stop sending span logs twice
- Resolve listener names once per method
- Pair row counts with queries in one pass

### Documentation
- **testing-app**: Record how Redis spans classify
- **testing-app**: List every demo scenario
- **testing-app**: Rewrap UI suite, note restock redirect
- Explain dropped requests on a Linux host
- Say CI builds Gradle bumps too
- Tighten the README's name paragraph
- Align the README with the site
- Describe the error page's Accept rule
- Count all seven detected switches
- Drop hyphen asides from ARCHITECTURE
- Correct BUILD.md's CI details
- Tidy two BUILD.md paragraphs
- List reveal.js in the frontend README
- Correct the row-count chip comment
- Drop temporal wording in overlayTargetKey
- State Logback packaging data once
- Drop filtered lines from the noise list
- Correct two stack-trace comments
- Point the glossary at the real matcher
- Name the exclusion checkbox's aria-label
- Note the capped-batch line in sql-view.js
- Tighten the trace-detail.css header
- State the stalled-reload abort per engine
- Name the walk that keeps cycles from Spring
- Say which paths sit beside the resolver
- Say what the auto-security profile does
- Split the PeekabootPaths Javadoc into sentences
- State the window guarantee in one place
- Say how async listing rows are assembled
- Say what guards the Hibernate formatter
- List every backend package in the tree
- List every switch the defaults processor sets
- Give the singular omitted-sets marker
- Turn hyphen asides into plain sentences
- Open the README with the site tagline
- Recapture the README screenshot
- Drop the stale wrapper version

### Internal changes
- Let the overlay own its open target
- Count span logs from the trace's logs
- Share one stack-trace style block
- Read an unset locale as undefined
- Rename spans.js queryLabel
- Check the async marker in one place
- Give async membership its own class
- Classify root spans without a mapper
- Share the autowire-candidate lookup
- Drop an always-true class condition
- Replace the null SQL formatter
- Share one set of stack-trace rules
- Pass the original request to inject
- Fill error-page templates in one pass
- Move the error-page resolvers to backend
- Move bind-parameter capture to backend
- Drop capture's mid-tick trimming
- Map trace logs in their own class
- Derive dashboard paths from BASE_PATH
- Drop the dead blank filter
- Skip refolds for synchronous survivors
- Count and list a detail's logs from one read
- Let SeriesRings alone guard its rings
- Drop the unreachable nested-entry skip
- Share one count-with-unit formatter

### Tests
- Stop the theme tests racing boot recovery
- **testing-app**: Add an inventory on MySQL
- **testing-app**: Cache product lookups in Redis
- **testing-app**: Listen for placed orders
- **testing-app**: Enable the secrets toggle
- **testing-app**: Opt in to every insights panel
- **testing-app**: Add a slow statistics query
- **testing-app**: Let async enrichment fail
- **testing-app**: Restock in one JDBC batch
- **testing-app**: Place orders through forms
- **testing-app**: Look up profiles on httpbin
- **testing-app**: Link every scenario in a nav
- **testing-app**: Wait for go-httpbin over HTTP
- **testing-app**: Share the demo nav reader
- **testing-app**: Run the restart IT in isolation
- **testing-app**: Assert async task span fails
- **test-support**: Add intentional log filter
- **backend**: Assert the warnings tests provoke
- **testing-app**: Assert the challenge warning
- **testing-app**: Quiet two third-party warnings
- **testing-app**: Hide provoked console lines
- **testing-app**: Add a nested async scenario
- Point the plural-rule comment the right way
- Drop the unused /api/data endpoint
- Keep insights routes installed while live
- Drive the slow header test with real traces
- Remove the orders the tabs tests seed
- Run container classes apart from the browser
- Fold several throwables in one trace
- Split DashboardTabsIT by tab
- Hide the absent-subsystem series warning
- Point masking comments at existing tests
- Remove the orders the fixture ITs seed
- Hide a tab whose feature is really off
- Challenge the bare dashboard path
- Guard every version both builds declare
- Keep Logback configured across contexts
- Store the slow-header traces directly
- Boot a context with no span exporter
- Name the guard test for what it asserts
- Keep unstarted log filters neutral
- Drop the decorator test's own recorder
- One exception fixture for the error view
- Name the listed-ids extraction once
- Give AsyncTraceCaptureIT an explicit store
- Cover the include dispatch with an IT
- Keep INFO logs off the test console
- Keep the Spring banner off the test console
- Stop calling deprecated APIs
- Share the servlet security exclusions
- Read span jump flash and focus in one poll
- Name the commit a release-notes check fails on
- Fail one lockstep row when its value is missing
- Drop a log-filter test that cannot fail
- Pin the status kept above a cyclic cause
- Cover a blank switch read through its env name
- Derive unset switch defaults from conditions
- Drop a doubly registered auto-configuration
- Refold after eviction in the window test
- Use the listed-ids helper in the Slow test
- Race snapshot and capture against ticks
- Import the servlet security exclusions
- **testing-app**: Write cache synchronously
- Count reloads by document request

### Build, CI and chores
- **deps-dev**: Bump html-validate in /peekaboot-frontend
- Wait for Central on repo1, not the Portal
- **deps**: Bump org.apache.maven:apache-maven from 3.9.16 to 3.10.0
- **deps-dev**: Bump globals in /peekaboot-frontend
- **deps-dev**: Bump stylelint in /peekaboot-frontend
- **deps-dev**: Bump eslint in /peekaboot-frontend
- **testing-app**: Comment the datasource key
- Skip the ITs in release:perform
- **deps**: Bump checkstyle from 14.1.0 to 14.3.0
- Use setup-java's current input names
- Fix the .iml ignore pattern
- **deps**: Bump com.diffplug.spotless:spotless-maven-plugin
- Drop the Spring Security dependency
- Send Central credentials under Maven 3.10
- Build and auto-merge Gradle Dependabot bumps
- **deps**: Bump gradle-wrapper from 9.8.0 to 9.8.1
- **deps**: Bump com.diffplug.spotless:spotless-plugin-gradle
- Use the shared commit-subject check
- Build dev after a Dependabot merge
- Validate the Gradle wrapper before it runs
- Pass the release version through env
- Describe prepare-build's server-id callers
- Keep internal commits off the release page
- File a second test-only fix under Tests
- List dependency upgrades on the release page
- Explain a release-notes check that cannot run
- Give SpotBugs the OpenTelemetry SDK
- Keep npm quiet in the Gradle build
- Let a failed command fail the harness setup
- Let a failed lookup stop the release scripts
- Keep CI and testing-app commits off the site
- Pick the build goal by the publishing secret
- Build Gradle on every Dependabot branch
- List only runtime bumps as dependency upgrades
- Align the Dependabot merge with its osslabz source
- Pin slf4j-api in Gradle as Maven does
- Show npm's output under Gradle's --info
- **deps**: Bump com.github.spotbugs:spotbugs-maven-plugin
- Run SpotBugs 4.10.5 in the Gradle build
- **deps**: Bump actions/setup-java in /.github/actions/prepare-build
- Bump central-publishing to 0.12.0

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/1.0.0...1.1.0

## 1.0.0 - 2026-09-30

### Breaking changes
- Fold the Internal trace type into Unknown. Traces with a kindless root span now report UNKNOWN; filter on rootActionType=UNKNOWN where you used INTERNAL.

### Features
- Exclude pool traces with a checkbox
- [**breaking**] Fold the Internal trace type into Unknown
- Fit the trace type filter on one row
- Capture the bind parameters of JDBC queries
- Format queries and serve their parameters
- Tokenize SQL for highlighting
- Copy the text a view shows at click time
- Highlight SQL and substitute its parameters
- Show a span's logs in its details
- Expand a span's query and logs in place

### Dependency upgrades
- **deps**: Bump net.osslabz:jdbc-url-parser from 0.1.1 to 0.1.2

### Tests
- **testing-app**: Add person view and edit pages

### Build, CI and chores
- **deps**: Bump org.slf4j:slf4j-api from 2.0.19 to 2.0.20
- **deps**: Bump net.sourceforge.pmd:pmd-core from 7.27.0 to 7.28.0
- Pin pmd-core and pmd-java to one property
- Pin surefire and failsafe to one property
- **deps**: Bump com.diffplug.spotless:spotless-maven-plugin
- **deps**: Bump com.diffplug.spotless:spotless-plugin-gradle
- **deps**: Bump gradle-wrapper from 9.7.1 to 9.8.0

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/0.3.0...1.0.0

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
- Capture a failed request as a server error
- **stack-trace**: Classify suppressed frames
- **stack-trace**: Merge adjacent application runs
- **error-page**: Ask FoldedTrace what to reveal

### Dependency upgrades
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
- Keep the jump highlight on a hovered row
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
- **deps-dev**: Bump org.apache.maven.plugins:maven-deploy-plugin
- **deps-dev**: Bump org.apache.maven.plugins:maven-install-plugin
- **deps-dev**: Bump org.codehaus.mojo:exec-maven-plugin
- Publish the site with the release
- Wait for the build instead of auto-merge
- Move the testing app off isbn-scanner's port
- Stop the dependabot merge waiting on itself
- Pass GH_REPO to the Dependabot merge
- Lint the frontend's JS, CSS and HTML
- Publish branch snapshots to Maven Central
- Take the snapshot version from a shared action
- Track the shared action by its major tag
- **deps**: Bump com.microsoft.playwright:playwright
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

### Performance
- Apply the audit's performance fixes
- Cache the bundled font for a year

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
- **deps**: Bump org.apache.maven.plugins:maven-failsafe-plugin
- **deps**: Bump io.github.git-commit-id:git-commit-id-maven-plugin
- **deps**: Bump org.apache.maven.plugins:maven-surefire-plugin
- **deps**: Bump com.diffplug.spotless:spotless-maven-plugin
- **deps**: Bump com.github.spotbugs:spotbugs-maven-plugin
- Apply the audit's build fixes
- Share the build preparation steps
- **deps**: Bump com.diffplug.spotless:spotless-plugin-gradle
- **deps**: Bump org.springdoc:springdoc-openapi-starter-webmvc-ui
- Add a nightly cross-browser workflow
- **deps**: Bump actions/setup-java in /.github/actions/prepare-build
- Generate grouped release notes
- **deps**: Bump WyriHaximus/github-action-get-previous-tag
- Publish release data to the website
- Fail the release-data render on a broken pipe
- Reduce a first release to one line

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/0.1.0...0.2.0

## 0.1.0 - 2026-09-04

Initial release.

