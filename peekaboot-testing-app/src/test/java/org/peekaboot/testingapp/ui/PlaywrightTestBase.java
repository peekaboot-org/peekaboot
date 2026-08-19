package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class PlaywrightTestBase {

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    protected int port;

    protected Page page;
    protected String baseUrl;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openPage() {
        baseUrl = "http://localhost:" + port;
        page = browser.newContext().newPage();
    }

    @AfterEach
    void closePage() {
        if (page != null) {
            page.context().close();
        }
    }

    protected void openDashboard() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        page.waitForSelector("#dashboard-tab.active");
    }

    protected void openPersonsPage() {
        page.navigate(baseUrl + "/persons");
        page.waitForSelector("#peekaboot-toolbar-host");
    }

    /** Resolved value of a CSS custom property on the first match of {@code selector}. */
    protected String cssVar(String selector, String property) {
        return (String) page.evalOnSelector(selector,
                "(el, prop) => getComputedStyle(el).getPropertyValue(prop).trim()", property);
    }

    /** Seeds the shared theme preference before any Peekaboot script runs. */
    protected void setStoredTheme(String theme) {
        page.addInitScript("localStorage.setItem('peekaboot-theme', '" + theme + "');");
    }
}
