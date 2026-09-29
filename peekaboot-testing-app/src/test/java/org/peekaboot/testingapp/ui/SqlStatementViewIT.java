package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A query's statement in the trace overlay against a real lookup: the person detail page binds
 * the person's id, which the SQL text never shows. The expected SQL is read back from the
 * insights API, so a Hibernate upgrade that renames its aliases changes both sides at once.
 */
class SqlStatementViewIT extends SeededPersonTestBase {

    @Test
    void aQuerySpansDetailsShowItsStatementParametersAndToggles() {
        JsonNode lookup = openDetailPageLookup();
        String spanId = lookup.path("spanId").asString();
        toolbar.openOverlay();

        overlay.click(".pk-gantt-row[data-span-id='" + spanId + "'] .pk-gantt-name__toggle");

        assertStatementView("#pk-span-details-" + spanId, lookup.path("statement"));
    }

    @Test
    void theQueriesTabShowsTheSameStatementView() {
        JsonNode lookup = openDetailPageLookup();
        toolbar.openOverlay();

        overlay.openTab("queries");

        assertStatementView(queryItem(lookup), lookup.path("statement"));
    }

    // Reading the clipboard back needs the clipboard-read permission, which only Chromium has.
    @Tag("chromium-only")
    @Test
    void theCopyButtonCopiesExactlyTheSqlShown() {
        page.context().grantPermissions(List.of("clipboard-read", "clipboard-write"));
        JsonNode lookup = openDetailPageLookup();
        toolbar.openOverlay();
        overlay.openTab("queries");
        String scope = queryItem(lookup);
        overlay.waitFor(scope + " .pk-sql__code");
        overlay.click(scope + " .pk-sql__substitute");

        overlay.click(scope + " .pk-copy");

        overlay.waitUntil(
                "(root, sel) => root.querySelector(sel).classList.contains('pk-copy--copied')", scope + " .pk-copy");
        assertThat(overlay.text(scope + " .pk-copy__status")).isEqualTo("Copied");
        assertThat((String) page.evaluate("() => navigator.clipboard.readText()"))
                .isEqualTo(overlay.text(scope + " .pk-sql__code"))
                .endsWith("=" + person.getId());
    }

    /** The persons list runs one findAll(): prepared, but with nothing bound. */
    @Test
    void aQueryWithoutParametersOffersNeitherTheListNorSubstitution() {
        openPersonsPage();
        JsonNode trace = awaitTrace(toolbar.traceId(), "trace => (trace.queries || []).length > 0");
        toolbar.openOverlay();
        overlay.openTab("queries");
        String scope = queryItem(trace.path("queries").get(0));
        overlay.waitFor(scope + " .pk-sql__code");

        assertThat(count(scope + " .pk-sql__params")).isZero();
        assertThat(count(scope + " .pk-sql__substitute")).isZero();
        assertThat(count(scope + " .pk-sql__format"))
                .as("Hibernate is on this app's classpath")
                .isEqualTo(1);
    }

    /** Loads the person's detail page and returns the insights API's entry for its one parameterised query. */
    private JsonNode openDetailPageLookup() {
        page.navigate(baseUrl + "/persons/" + person.getId());
        JsonNode trace = awaitTrace(
                toolbar.traceId(),
                "trace => (trace.queries || []).some(q => q.statement && q.statement.parameters.length === 1)");
        for (JsonNode query : trace.path("queries")) {
            if (query.path("statement").path("parameters").size() == 1) {
                return query;
            }
        }
        throw new AssertionError("no parameterised query in " + trace.path("queries"));
    }

    private void assertStatementView(String scope, JsonNode statement) {
        String id = String.valueOf(person.getId());
        String code = scope + " .pk-sql__code";
        overlay.waitFor(code);

        assertThat(overlay.text(code))
                .as("raw by default")
                .isEqualTo(statement.path("text").asString());
        assertThat(count(code + " .pk-sql__keyword")).as("highlighted").isPositive();
        assertThat(count(code + " .pk-sql__placeholder")).isEqualTo(1);
        assertThat(texts(scope + " .pk-sql__param-list li")).containsExactly(id);

        overlay.click(scope + " .pk-sql__format");
        assertThat(overlay.text(code)).isEqualTo(statement.path("formatted").asString());

        overlay.click(scope + " .pk-sql__substitute");
        assertThat(overlay.text(code))
                .isEqualTo(statement.path("formatted").asString().replace("?", id));
        assertThat(count(code + " .pk-sql__placeholder")).isZero();
        assertThat(texts(code + " .pk-sql__number")).contains(id);
    }

    private static String queryItem(JsonNode query) {
        return ".pk-query-item[data-span-id='" + query.path("spanId").asString() + "']";
    }

    private int count(String selector) {
        return ((Number) overlay.evaluate("(root, sel) => root.querySelectorAll(sel).length", selector)).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> texts(String selector) {
        return (List<String>)
                overlay.evaluate("(root, sel) => [...root.querySelectorAll(sel)].map(e => e.textContent)", selector);
    }
}
