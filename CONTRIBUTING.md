# Contributing

- Bugs and proposals go to the
  [issue tracker](https://github.com/peekaboot-org/peekaboot/issues), a vulnerability through
  [`SECURITY.md`](SECURITY.md).
- [`BUILD.md`](BUILD.md) has the build, the gates and the commands. `./mvnw clean verify`
  passes before a change is proposed.
- [`docs/TESTING.md`](docs/TESTING.md) has the test conventions. A change comes with a test
  that fails without it.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/GLOSSARY.md`](docs/GLOSSARY.md)
  say how the code is put together.
- Commit subjects are [Conventional Commits](https://www.conventionalcommits.org/), and the
  type decides the next version. See
  [How the next version is chosen](BUILD.md#how-the-next-version-is-chosen).
- A feature or fix is one commit. Its subject and body are its entry in the release notes, so
  the body says what changed for the person upgrading. See
  [Release notes](BUILD.md#release-notes).
- Branch from `dev` and open the pull request against `dev`.
