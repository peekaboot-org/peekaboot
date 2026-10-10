package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Behind a context path every nav target must carry the prefix, or every entry 404s. Same
 * configuration as ContextPathToolbarIT, so both share one cached context.
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/app")
class ContextPathNavigationIT extends PlaywrightTestBase {

    private static final String CONTEXT_PATH = "/app";

    @Override
    protected String contextPath() {
        return CONTEXT_PATH;
    }

    @Test
    void everyNavTargetCarriesTheContextPath() {
        page.navigate(baseUrl + CONTEXT_PATH + "/persons");

        @SuppressWarnings("unchecked")
        List<String> targets = (List<String>) page.evaluate("""
                () => [...document.querySelectorAll('#demo-nav a, #demo-nav form')]
                    .map(entry => entry.getAttribute(entry.tagName === 'A' ? 'href' : 'action'))
                """);

        assertThat(targets)
                .hasSize(NavigationIT.ENTRIES.size())
                .allSatisfy(target -> assertThat(target).startsWith(CONTEXT_PATH + "/"));
    }
}
