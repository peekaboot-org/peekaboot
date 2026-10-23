package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises trace-detail/log-entry.js directly, with the sheets that colour it. */
class LogEntryIT extends PlaywrightTestBase {

    /** Each level takes the text token of its badge tier (severity.js's logLevelVariant). */
    @Test
    void eachLevelIsColouredByItsBadgeTiersTextToken() {
        openBlankFixture();
        page.addStyleTag(new Page.AddStyleTagOptions().setUrl("/peekaboot/ui/assets/tokens.css"));
        page.addStyleTag(new Page.AddStyleTagOptions().setUrl("/peekaboot/ui/trace-detail/trace-detail.css"));

        Object colours = importModule("trace-detail/log-entry.js", """
                (() => {
                    const colour = element => getComputedStyle(element).color;
                    const token = name => {
                        const probe = document.createElement('span');
                        probe.style.color = `var(${name})`;
                        document.body.append(probe);
                        return colour(probe);
                    };
                    const tokens = {ERROR: '--pk-danger', WARN: '--pk-warning-text', INFO: '--pk-info-text',
                        DEBUG: '--pk-text-muted', TRACE: '--pk-text-muted'};
                    return Object.fromEntries(Object.entries(tokens).map(([level, name]) => {
                        const entry = m.logEntry({level, message: 'm', timestamp: 0}, {});
                        document.body.append(entry);
                        return [level, colour(entry.querySelector('.pk-log__level')) === token(name)];
                    }));
                })()
                """);

        assertThat(colours).isEqualTo(Map.of("ERROR", true, "WARN", true, "INFO", true, "DEBUG", true, "TRACE", true));
    }
}
