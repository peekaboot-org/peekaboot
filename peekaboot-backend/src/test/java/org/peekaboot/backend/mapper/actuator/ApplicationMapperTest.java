package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.InfoResponse;
import org.peekaboot.backend.actuator.parsed.SpringInfo;
import org.peekaboot.backend.domain.application.ApplicationInfo;
import org.peekaboot.backend.domain.application.GitInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.boot.info.GitProperties;

class ApplicationMapperTest {

    private final ApplicationMapper mapper = new ApplicationMapper(new MaskingEngine());

    /** Entries as Boot holds them, the {@code git.} prefix already stripped. */
    private static GitProperties git(String... keysAndValues) {
        Properties entries = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            entries.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new GitProperties(entries);
    }

    @Test
    void map_shouldExtractBuildInfo() {
        InfoResponse info = new InfoResponse(Map.of("artifact", "my-app", "version", "1.0.0"), null, null, null);
        ApplicationInfo result = mapper.map(info, null, null, false);
        assertThat(result.build()).containsEntry("artifact", "my-app");
        assertThat(result.build()).containsEntry("version", "1.0.0");
    }

    /** {@code info.build} is a free-form map the consuming app fills itself, so its values are masked like any other. */
    @Test
    void map_shouldMaskASensitiveKeyInBuildInfo() {
        InfoResponse info = new InfoResponse(Map.of("artifact", "my-app", "apiKey", "hunter2"), null, null, null);

        ApplicationInfo result = mapper.map(info, null, null, false);

        assertThat(result.build()).containsEntry("artifact", "my-app").containsEntry("apiKey", "******");
    }

    @Test
    void map_shouldReturnBuildInfoVerbatimWhenUnmaskIsTrue() {
        InfoResponse info = new InfoResponse(Map.of("apiKey", "hunter2"), null, null, null);

        assertThat(mapper.map(info, null, null, true).build()).containsEntry("apiKey", "hunter2");
    }

    /** The commit time in git-commit-id's default format, served as an instant. */
    @Test
    void map_shouldExtractGitInfo() {
        GitProperties git =
                git("branch", "main", "commit.id.abbrev", "abc1234", "commit.time", "2024-01-01T11:00:00+0100");

        ApplicationInfo result = mapper.map(null, git, null, false);

        assertThat(result.git())
                .isEqualTo(new GitInfo("main", new GitInfo.Commit("abc1234", Instant.parse("2024-01-01T10:00:00Z"))));
    }

    /** A remote URL can carry the token it was cloned with, and a committer's address is personal data. */
    @Test
    void map_shouldLeaveEveryOtherGitEntryBehind() {
        GitProperties git = git(
                "branch", "main",
                "commit.id.abbrev", "abc1234",
                "remote.origin.url", "https://xar:ghp_secrettoken@github.com/acme/orders.git",
                "commit.user.email", "xar@example.com");

        assertThat(mapper.map(null, git, null, false).git())
                .isEqualTo(new GitInfo("main", new GitInfo.Commit("abc1234", null)));
    }

    @Test
    void map_shouldShortenACommitIdWithoutAnAbbreviation() {
        assertThat(mapper.map(null, git("commit.id", "1a2b3c4d5e6f7a8b"), null, false)
                        .git())
                .isEqualTo(new GitInfo(null, new GitInfo.Commit("1a2b3c4", null)));
    }

    /** git-commit-id's {@code commitIdGenerationMode=full}, the testing app's, writes {@code commit.id.full} beside the abbreviation. */
    @Test
    void map_shouldShowTheAbbreviationOfAFullModeFile() {
        GitProperties git = git("commit.id.full", "1a2b3c4d5e6f7a8b", "commit.id.abbrev", "1a2b3c4");

        assertThat(mapper.map(null, git, null, false).git())
                .isEqualTo(new GitInfo(null, new GitInfo.Commit("1a2b3c4", null)));
    }

    @Test
    void map_shouldFallBackToTheFullIdWhenTheAbbreviationIsMissing() {
        assertThat(mapper.map(null, git("commit.id.full", "1a2b3c4d5e6f7a8b"), null, false)
                        .git())
                .isEqualTo(new GitInfo(null, new GitInfo.Commit("1a2b3c4d5e6f7a8b", null)));
    }

    @Test
    void map_shouldLeaveTheIdAbsentWhenTheCommitCarriesNone() {
        assertThat(mapper.map(null, git("commit.time", "2024-01-01T11:00:00+0100"), null, false)
                        .git())
                .isEqualTo(new GitInfo(null, new GitInfo.Commit(null, Instant.parse("2024-01-01T10:00:00Z"))));
    }

    @Test
    void map_shouldLeaveTheCommitAbsentWhenGitCarriesNone() {
        assertThat(mapper.map(null, git("branch", "main"), null, false).git()).isEqualTo(new GitInfo("main", null));
    }

    /** git-commit-id still writes the build time and version where it finds no repository. */
    @Test
    void map_shouldLeaveGitAbsentWhenTheFileNamesNeitherBranchNorCommit() {
        GitProperties git = git("build.time", "1704103200000", "build.version", "1.0.0");

        assertThat(mapper.map(null, git, null, false).git()).isNull();
    }

    @Test
    void map_shouldLeaveTheVendorAbsentWhenJavaCarriesNone() {
        InfoResponse info = new InfoResponse(null, new InfoResponse.JavaDescriptor(null, "21.0.1"), null, null);

        ApplicationInfo result = mapper.map(info, null, null, false);

        assertThat(result.javaVersion()).isEqualTo("21.0.1");
        assertThat(result.javaVendor()).isNull();
    }

    @Test
    void map_shouldExtractJavaInfo() {
        InfoResponse info = new InfoResponse(
                null,
                new InfoResponse.JavaDescriptor(
                        new InfoResponse.JavaDescriptor.VendorDescriptor("Eclipse Adoptium"), "21.0.1"),
                null,
                null);
        ApplicationInfo result = mapper.map(info, null, null, false);
        assertThat(result.javaVersion()).isEqualTo("21.0.1");
        assertThat(result.javaVendor()).isEqualTo("Eclipse Adoptium");
    }

    @Test
    void map_shouldExtractSpringVersions() {
        SpringInfo spring = new SpringInfo("3.2.0", "6.1.2");
        ApplicationInfo result = mapper.map(null, null, spring, false);
        assertThat(result.springBootVersion()).isEqualTo("3.2.0");
        assertThat(result.springFrameworkVersion()).isEqualTo("6.1.2");
    }

    @Test
    void map_shouldHandleNullInputs() {
        ApplicationInfo result = mapper.map(null, null, null, false);
        assertThat(result.build()).isEmpty();
        assertThat(result.git()).isNull();
        assertThat(result.javaVersion()).isNull();
        assertThat(result.springBootVersion()).isNull();
    }
}
