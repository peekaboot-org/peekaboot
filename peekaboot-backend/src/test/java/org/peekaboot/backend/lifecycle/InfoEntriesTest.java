package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class InfoEntriesTest {

    @Test
    void everyEntryIsCarriedOverNotJustTheOnesWeRead() {
        Properties properties = new Properties();
        properties.setProperty("version", "1.2.3");
        properties.setProperty("time", "1756000000000");
        properties.setProperty("ci.pipeline", "4711");

        assertThat(InfoEntries.of(new BuildProperties(properties)))
                .containsEntry("version", "1.2.3")
                .containsEntry("time", "1756000000000")
                .containsEntry("ci.pipeline", "4711");
    }

    @Test
    void absentInfoIsAnEmptyMapRatherThanNull() {
        assertThat(InfoEntries.of(null)).isEmpty();
    }
}
