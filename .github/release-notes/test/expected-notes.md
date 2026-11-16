## Unreleased

### Features
- **Add something after the last tag.** It shows up in the draft with its description.

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/1.5.0...dev

## 1.5.0 - 2026-03-01

### Bug fixes
- Correct the rename

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/1.4.0...1.5.0

## 1.4.0 - 2026-03-01

### Breaking changes
- Drop the legacy endpoint. /v1 is gone, use /v2
- **api**: Rename the field
- **store**: Key traces by id

### Features
- [**breaking**] **Drop the legacy endpoint.** The /v1 endpoint answered with the old payload.
- **api**: [**breaking**] Rename the field
- **Add the second thing.** The second thing lists every entry and says how many it left out.

  Set thing.limit to list more.

### Bug fixes
- **Keep the order stable.** Two entries of the same age swapped places.

### Performance
- Cache the lookups

### Dependency upgrades
- **deps**: Bump com.cronutils:cron-utils from 9.2.1 to 9.2.2

### Reverts
- Restore the old rule

### Documentation
- Explain the new endpoint

### Internal changes
- **store**: [**breaking**] **Key traces by id.** A custom store has to implement findById.
- Tidy the helpers
- Tidy the helpers

### Tests
- **testing-app**: Add a demo page
- **test**: Pin the flaky wait
- Cover the new endpoint

### Build, CI and chores
- **deps**: Bump org.apache.maven.plugins:maven-surefire-plugin
- **deps-dev**: Bump com.example:plugin

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/1.3.0...1.4.0

## 1.3.0 - 2026-02-01

### Features
- Add the first thing

### Bug fixes
- Correct the first thing

**Full changelog**: https://github.com/peekaboot-org/peekaboot/compare/1.2.0...1.3.0

