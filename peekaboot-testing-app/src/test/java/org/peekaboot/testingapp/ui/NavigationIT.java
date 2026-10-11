package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.options.RequestOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The nav is how a person running the app finds every scenario, so each entry must name the
 * target its scenario lives at, and every page the app renders must carry it.
 */
class NavigationIT extends SeededPersonTestBase {

    /** Every entry as "label -> target"; a form reads as its method, action and fields. */
    static final List<String> ENTRIES = List.of(
            "Persons -> /persons",
            "Orders (N+1, two datasources, outbound call) -> /orders",
            "Order statistics (slow query) -> /orders/stats",
            "Profile lookup -> /persons/1",
            "Slow profile lookup -> /persons/1?profile=slow",
            "Profile service down -> /persons/1?profile=unavailable",
            "Async enrichment -> /orders/enrich",
            "Failing async enrichment -> /orders/enrich?fail=true",
            "Nested async enrichment -> /orders/enrich?notify=true",
            "Logged error, page still 200 -> /?error=true",
            "Exception, error page -> /boom",
            "Forward (nested dispatch) -> /people",
            "Slow report (JSON) -> /api/orders/1/report",
            "Place order -> post /orders customerId=1&sku=WIDGET-1&quantity=1",
            "Order unknown product (400) -> post /orders customerId=1&sku=NO-SUCH-PRODUCT&quantity=1",
            "Order more than in stock (409) -> post /orders customerId=1&sku=GADGET-1&quantity=100000",
            "Restock all products (JDBC batch) -> post /inventory/restock",
            "Peekaboot dashboard -> /peekaboot",
            "Swagger UI -> /swagger-ui.html");

    private static final String DESCRIBE_ENTRIES = DemoNav.mapEntries("""
            entry.tagName === 'A'
                ? entry.textContent.trim() + ' -> ' + %1$s
                : entry.querySelector('button').textContent.trim() + ' -> '
                    + [entry.method, %1$s, new URLSearchParams(new FormData(entry)).toString()]
                        .filter(Boolean).join(' ')
            """.formatted(DemoNav.TARGET));

    private static final String DESCRIBE_FORMS = """
            () => [...document.querySelectorAll('#demo-nav form')]
                .map(form => [form.getAttribute('action'), new URLSearchParams(new FormData(form)).toString()])
            """;

    @Test
    void everyEntryNamesItsScenariosTarget() {
        page.navigate(baseUrl + "/persons");

        assertThat(entries()).containsExactlyElementsOf(ENTRIES);
    }

    @Test
    void everyPageTheAppRendersCarriesTheNav() {
        for (String path : List.of(
                "/persons",
                "/orders",
                "/orders/stats",
                "/persons/" + person.getId(),
                "/persons/" + person.getId() + "/edit")) {
            page.navigate(baseUrl + path);

            assertThat(entries()).as("the nav on %s", path).hasSize(ENTRIES.size());
        }
    }

    /**
     * Only the links that need no Flyway seed and log nothing by design. /persons/1 and the report
     * read seeded rows the test profile lacks, and the error and async scenarios are asserted,
     * logs included, by PersonPagesIT, ProfileLookupTraceIT, OrderTraceCaptureIT, LogCaptureIT
     * and AsyncTraceCaptureIT.
     */
    @Test
    void everySeedFreeQuietLinkAnswersOk() {
        for (String path :
                List.of("/persons", "/orders", "/orders/stats", "/people", "/peekaboot", "/swagger-ui.html")) {
            assertThat(page.request().get(baseUrl + path).status())
                    .as("GET %s, redirects followed", path)
                    .isEqualTo(200);
        }
    }

    @Test
    void everyFormAnswersWithItsScenariosStatus() {
        page.navigate(baseUrl + "/persons");
        @SuppressWarnings("unchecked")
        List<List<String>> forms = (List<List<String>>) page.evaluate(DESCRIBE_FORMS);

        List<Integer> statuses = forms.stream()
                .map(form -> page.request()
                        .post(
                                baseUrl + form.get(0),
                                RequestOptions.create()
                                        .setHeader("Content-Type", "application/x-www-form-urlencoded")
                                        .setData(form.get(1))
                                        .setMaxRedirects(0))
                        .status())
                .toList();

        assertThat(statuses).containsExactly(302, 400, 409, 302);
    }

    @SuppressWarnings("unchecked")
    private List<String> entries() {
        return (List<String>) page.evaluate(DESCRIBE_ENTRIES);
    }
}
