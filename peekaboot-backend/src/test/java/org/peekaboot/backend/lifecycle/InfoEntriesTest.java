package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

class InfoEntriesTest {

    @Test
    void aBuildEntryNoViewReadsIsLeftBehind() {
        Properties properties = new Properties();
        properties.setProperty("version", "1.2.3");
        properties.setProperty("time", "1756000000000");
        properties.setProperty("ci.pipeline", "4711");

        assertThat(InfoEntries.of(new BuildProperties(properties)))
                .containsEntry("version", "1.2.3")
                .containsEntry("time", "1756000000000")
                .doesNotContainKey("ci.pipeline");
    }

    /**
     * The two entries git-commit-id emits that carry something personal or secret: an
     * HTTPS remote can hold the token it is cloned with, and the building user's address
     * is personal data.
     */
    @Test
    void theRemoteUrlAndTheBuildUsersAddressAreLeftBehind() {
        Properties properties = new Properties();
        properties.setProperty("branch", "dev");
        properties.setProperty("commit.id.full", "abc1234def5678");
        properties.setProperty("commit.id.abbrev", "abc1234");
        properties.setProperty("build.version", "1.2.3");
        properties.setProperty("remote.origin.url", "https://xar:ghp_secrettoken@github.com/acme/orders.git");
        properties.setProperty("build.user.email", "dev@example.com");

        assertThat(InfoEntries.of(new GitProperties(properties)))
                .containsOnlyKeys("branch", "commit.id.full", "commit.id.abbrev", "build.version");
    }

    @Test
    void absentInfoIsAnEmptyMapRatherThanNull() {
        assertThat(InfoEntries.of(null)).isEmpty();
    }
}
