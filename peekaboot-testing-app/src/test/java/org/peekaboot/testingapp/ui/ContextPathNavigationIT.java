package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Behind a context path every nav target must carry the prefix, or every entry 404s. */
class ContextPathNavigationIT extends ContextPathTestBase {

    @Test
    void everyNavTargetCarriesTheContextPath() {
        openPersonsPage();

        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) page.evaluate(DemoNav.mapEntries(DemoNav.TARGET));

        assertThat(targets)
                .hasSize(NavigationIT.ENTRIES.size())
                .allSatisfy(target -> assertThat(target).startsWith(CONTEXT_PATH + "/"));
    }
}
