package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.example.security.PeekabootSecurityConfig;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * What {@link PeekabootSecurityConfig} looks like from a real browser, which is where its
 * one non-obvious consequence lives: the dev toolbar is injected into the application's own
 * pages, but it loads from {@code /peekaboot/ui/toolbar/toolbar.js} - a path the chain
 * gates. A reader who secures the dashboard also turns the toolbar off for everyone outside
 * the role, on every page of their application, and these tests pin both halves of that.
 *
 * <p>{@code SecuredPeekabootIntegrationTest} covers the HTTP contract itself; this covers
 * only what a browser additionally shows.
 */
@SpringBootTest(
        classes = {TestingApp.class, PeekabootSecurityConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// inheritProfiles = false for the same reason as ScreenshotCapture: PlaywrightTestBase
// carries @ActiveProfiles("test"), and that profile is the one that excludes the servlet
// security auto-configuration this whole class exists to exercise.
@ActiveProfiles(profiles = "security", inheritProfiles = false)
class SecuredDashboardTest extends PlaywrightTestBase {

    private static final String TOOLBAR_SCRIPT = "/peekaboot/ui/toolbar/toolbar.js";

    /** The credentials PeekabootSecurityConfig's in-memory store holds for ROLE_ADMIN. */
    @Override
    protected Page browserContextPage() {
        return browser.newContext(newContextOptions().setHttpCredentials("admin", "admin-password"))
                .newPage();
    }

    @Test
    void theDashboardRendersForAnAdmin() {
        openDashboard();

        assertThat(page.textContent("h1")).isEqualTo("peekaboot");
        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
    }

    @Test
    void theToolbarResolvesItsMetricsForAnAdmin() {
        openPersonsPage();

        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-metrics').querySelector('.pk-stat') !== null");

        String metrics = (String) page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-metrics').textContent");
        assertThat(metrics).doesNotContain("?");
    }

    @Test
    void anAnonymousBrowserIsRefusedTheDashboard() {
        try (BrowserContext anonymous = browser.newContext(newContextOptions())) {
            Page anonymousPage = anonymous.newPage();

            Response response = anonymousPage.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");

            assertThat(response.status()).isEqualTo(401);
            assertThat(anonymousPage.locator("#overview-tab").count()).isZero();
        }
    }

    /**
     * The consequence worth documenting. The application's page is untouched - the Peekaboot
     * chain matches only its own paths - and the filter still injects the toolbar's bootstrap
     * markup, because that happens server-side with no idea who is asking. But the browser's
     * follow-up request for the toolbar module is a {@code /peekaboot/**} request like any
     * other, so it is refused and the toolbar never mounts: no shadow host, no bar.
     */
    @Test
    void theInjectedToolbarDoesNotLoadForAnAnonymousBrowser() {
        try (BrowserContext anonymous = browser.newContext(newContextOptions())) {
            Page anonymousPage = anonymous.newPage();
            List<Integer> toolbarScriptStatuses = new ArrayList<>();
            anonymousPage.onResponse(response -> {
                if (response.url().endsWith(TOOLBAR_SCRIPT)) {
                    toolbarScriptStatuses.add(response.status());
                }
            });

            Response response = anonymousPage.navigate(baseUrl + "/persons");
            anonymousPage.waitForLoadState(LoadState.NETWORKIDLE);

            assertThat(response.status()).isEqualTo(200);
            assertThat(anonymousPage.textContent("h1")).isEqualTo("Persons List");
            assertThat(anonymousPage.content())
                    .as("the filter injects the toolbar bootstrap regardless of who is asking")
                    .contains("peekaboot-toolbar-data");
            assertThat(toolbarScriptStatuses)
                    .as("the toolbar module is a /peekaboot/** request like any other")
                    .containsExactly(401);
            assertThat(anonymousPage.locator("#peekaboot-toolbar-host").count()).isZero();
        }
    }
}
