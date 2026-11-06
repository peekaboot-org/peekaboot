package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

class PeekabootPropertiesTest {

    private static PeekabootProperties bind(String property, String value) {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("peekaboot." + property, value)));
        return binder.bind("peekaboot", Bindable.of(PeekabootProperties.class)).get();
    }

    /** Folding decides only what a trace shows first; one control reveals the rest. */
    @Test
    void foldsStackTracesByDefault() {
        assertThat(new PeekabootProperties().getStackTrace().isFold()).isTrue();
    }

    @Test
    void theTraceJournalFlushesEverySecondAndFoldsAt64Megabytes() {
        PeekabootProperties.Storage.Traces traces =
                new PeekabootProperties().getStorage().getTraces();

        assertThat(traces.isEnabled()).isTrue();
        assertThat(traces.getFlushInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(traces.getMaxJournalSize()).isEqualTo(DataSize.ofMegabytes(64));
    }

    @Test
    void bindsTheTraceJournalSettings() {
        assertThat(bind("storage.traces.flush-interval", "250ms")
                        .getStorage()
                        .getTraces()
                        .getFlushInterval())
                .isEqualTo(Duration.ofMillis(250));
        assertThat(bind("storage.traces.max-journal-size", "8MB")
                        .getStorage()
                        .getTraces()
                        .getMaxJournalSize())
                .isEqualTo(DataSize.ofMegabytes(8));
    }

    /** A flush interval or a journal size of nothing names nothing, so it fails at binding. */
    @Test
    void refusesANonPositiveFlushIntervalOrJournalSize() {
        assertThatThrownBy(() -> bind("storage.traces.flush-interval", "0s"))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("peekaboot.storage.traces.flush-interval must be positive (PT0S configured)");
        assertThatThrownBy(() -> bind("storage.traces.flush-interval", "-1s"))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("peekaboot.storage.traces.flush-interval must be positive (PT-1S configured)");
        assertThatThrownBy(() -> bind("storage.traces.max-journal-size", "0B"))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("peekaboot.storage.traces.max-journal-size must be positive (0B configured)");
    }
}
