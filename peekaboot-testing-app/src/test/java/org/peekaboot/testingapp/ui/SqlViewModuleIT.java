package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** shared/sql-view.js against hand-built statements, imported into the blank fixture page. */
class SqlViewModuleIT extends PlaywrightTestBase {

    private static final String TWO_PARAMETERS = """
            {"text": "select * from t where a = ? and b = ?",
             "formatted": "select\\n    *\\nfrom t\\nwhere a = ? and b = ?",
             "parameters": [["'x'", "42"]]}
            """;

    private static final String PRELUDE = """
            const view = m.sqlView(JSON.parse(arg));
            document.body.append(view);
            const code = () => view.querySelector('.pk-sql__code').textContent;
            const has = selector => view.querySelector(selector) !== null;
            const click = selector => view.querySelector(selector).click();
            const texts = selector => [...view.querySelectorAll(selector)].map(e => e.textContent);
            """;

    private Object inView(String statementJson, String body) {
        return importModule("shared/sql-view.js", "(() => {" + PRELUDE + body + "})()", statementJson);
    }

    @Test
    void showsTheRawStatementFirstAndItsParametersAsANumberedList() {
        assertThat(inView(TWO_PARAMETERS, """
                        return [code(), view.querySelector('.pk-sql__format').getAttribute('aria-pressed'),
                                texts('.pk-sql__param-list li'), has('.pk-sql__keyword'), has('.pk-copy')];
                        """))
                .isEqualTo(List.of("select * from t where a = ? and b = ?", "false", List.of("'x'", "42"), true, true));
    }

    @Test
    void theFormattedToggleSwapsInTheFormattedSqlAndBack() {
        assertThat(inView(TWO_PARAMETERS, """
                        click('.pk-sql__format');
                        const formatted = [code(), view.querySelector('.pk-sql__format').getAttribute('aria-pressed')];
                        click('.pk-sql__format');
                        return [...formatted, code()];
                        """))
                .isEqualTo(List.of(
                        "select\n    *\nfrom t\nwhere a = ? and b = ?",
                        "true",
                        "select * from t where a = ? and b = ?"));
    }

    @Test
    void withoutFormattedSqlThereIsNoFormattedToggle() {
        assertThat(inView(
                        "{\"text\": \"select 1\", \"formatted\": null, \"parameters\": []}",
                        "return has('.pk-sql__format');"))
                .isEqualTo(false);
    }

    @Test
    void substitutionPutsEachLiteralInPlaceAsAHighlightedToken() {
        assertThat(inView(TWO_PARAMETERS, """
                        click('.pk-sql__substitute');
                        return [code(), texts('.pk-sql__string'), texts('.pk-sql__number'), has('.pk-sql__placeholder')];
                        """))
                .isEqualTo(List.of("select * from t where a = 'x' and b = 42", List.of("'x'"), List.of("42"), false));
    }

    @Test
    void substitutionCarriesAcrossTheFormattedToggle() {
        assertThat(inView(TWO_PARAMETERS, "click('.pk-sql__substitute'); click('.pk-sql__format'); return code();"))
                .isEqualTo("select\n    *\nfrom t\nwhere a = 'x' and b = 42");
    }

    @Test
    void aParameterCountThatDoesNotMatchThePlaceholdersOffersNoSubstitution() {
        assertThat(inView(
                        "{\"text\": \"select ? ?| ?\", \"formatted\": null, \"parameters\": [[\"1\", \"2\"]]}",
                        "return [has('.pk-sql__substitute'), code()];"))
                .isEqualTo(List.of(false, "select ? ?| ?"));
    }

    /** A pressed Substitute meeting SQL it does not fit must leave that SQL alone, and resume where it fits again. */
    @Test
    void theSubstituteToggleFollowsTheSqlShown() {
        assertThat(inView(
                        "{\"text\": \"select a from t where b = ?\", \"formatted\": \"select a\\nfrom t\\nwhere b ?| ?\","
                                + " \"parameters\": [[\"1\"]]}",
                        """
                        click('.pk-sql__substitute');
                        const raw = code();
                        click('.pk-sql__format');
                        const formatted = [has('.pk-sql__substitute'), code()];
                        click('.pk-sql__format');
                        return [raw, ...formatted, has('.pk-sql__substitute'), code()];
                        """))
                .isEqualTo(List.of(
                        "select a from t where b = 1",
                        false,
                        "select a\nfrom t\nwhere b ?| ?",
                        true,
                        "select a from t where b = 1"));
    }

    @Test
    void aBatchListsEachParameterSetAndOffersNoSubstitution() {
        assertThat(
                        inView(
                                "{\"text\": \"insert into t values (?)\", \"formatted\": null, \"parameters\": [[\"1\"], [\"2\"]]}",
                                "return [has('.pk-sql__substitute'), texts('.pk-sql__params-label'), texts('.pk-sql__param-list li')];"))
                .isEqualTo(List.of(false, List.of("Parameter set 1", "Parameter set 2"), List.of("1", "2")));
    }

    @Test
    void aStatementWithoutParametersShowsNoListAndNoSubstitution() {
        assertThat(inView(
                        "{\"text\": \"select 1\", \"formatted\": \"select\\n    1\", \"parameters\": []}",
                        "return [has('.pk-sql__params'), has('.pk-sql__substitute'), has('.pk-sql__format')];"))
                .isEqualTo(List.of(false, false, true));
    }
}
