package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises shared/sql.js in a real browser, imported from the running app - the SharedModuleIT pattern. */
class SqlModuleIT extends PlaywrightTestBase {

    /** Every token but whitespace, as "type text", so a test names the tokens it cares about. */
    private static final String SIGNIFICANT_TOKENS =
            "m.tokenize(arg).filter(t => t.type !== 'whitespace').map(t => t.type + ' ' + t.text)";

    @SuppressWarnings("unchecked")
    private List<String> significantTokens(String sql) {
        return (List<String>) importModule("shared/sql.js", SIGNIFICANT_TOKENS, sql);
    }

    @Test
    void classifiesEveryKindOfToken() {
        assertThat(
                        significantTokens(
                                "select \"Name\", `x` from t -- trailing\n/* block */ where a >= 1.5 and b = 'it''s' and c = ?"))
                .containsExactly(
                        "keyword select",
                        "quoted-identifier \"Name\"",
                        "punctuation ,",
                        "quoted-identifier `x`",
                        "keyword from",
                        "identifier t",
                        "comment -- trailing",
                        "comment /* block */",
                        "keyword where",
                        "identifier a",
                        "operator >=",
                        "number 1.5",
                        "keyword and",
                        "identifier b",
                        "operator =",
                        "string 'it''s'",
                        "keyword and",
                        "identifier c",
                        "operator =",
                        "placeholder ?");
    }

    @Test
    void aQuestionMarkInsideAStringCommentOrQuotedIdentifierIsNoPlaceholder() {
        assertThat(importModule(
                        "shared/sql.js",
                        "m.placeholderCount(m.tokenize(arg))",
                        "select '?', \"a?\" /* ? */ -- ?\n from t where x = ?"))
                .isEqualTo(1);
    }

    @Test
    void keywordsMatchInAnyCase() {
        assertThat(significantTokens("SELECT Select select"))
                .containsExactly("keyword SELECT", "keyword Select", "keyword select");
    }

    /** Captured SQL can be cut off; the rest of it belongs to the string or comment it was in. */
    @Test
    void anUnterminatedStringOrCommentRunsToTheEnd() {
        assertThat(significantTokens("select 'abc")).containsExactly("keyword select", "string 'abc");
        assertThat(significantTokens("select /* abc")).containsExactly("keyword select", "comment /* abc");
    }

    /** Highlighting must never change the text: copy and substitution both read it back from the tokens. */
    @Test
    void theTokensJoinBackToTheInputExactly() {
        assertThat(importModule(
                        "shared/sql.js",
                        "arg.every(sql => m.textOf(m.tokenize(sql)) === sql)",
                        List.of(
                                "",
                                "select $1, :name, @var, #tmp from \"t\"\"x\" where é = '🙂'",
                                "a::int || b <> c != d",
                                "select\n\t*\r\nfrom t;")))
                .isEqualTo(true);
    }

    @Test
    void renderSqlWrapsHighlightedTokensAndLeavesIdentifiersAsText() {
        assertThat(importModule("shared/sql.js", """
                        (() => {
                            const block = document.createElement('pre');
                            block.append(...m.renderSql(m.tokenize(arg)));
                            return [block.textContent, [...block.children].map(c => c.className + ' ' + c.textContent)];
                        })()
                        """, "select a from t where b = ?"))
                .isEqualTo(List.of(
                        "select a from t where b = ?",
                        List.of(
                                "pk-sql__keyword select",
                                "pk-sql__keyword from",
                                "pk-sql__keyword where",
                                "pk-sql__operator =",
                                "pk-sql__placeholder ?")));
    }

    /**
     * Captured SQL and bind literals carry user-controlled text into the overlay; renderSql must
     * show it as text, never parse it as markup. Unlike the test above, this one would fail
     * against an {@code innerHTML} renderer: the string literal and the bare tag would each
     * become a real element instead of text.
     */
    @Test
    void renderSqlNeverTurnsTokenTextIntoElements() {
        assertThat(importModule("shared/sql.js", """
                        (() => {
                            const block = document.createElement('pre');
                            block.append(...m.renderSql(m.tokenize(arg)));
                            return [block.textContent, block.querySelectorAll('img, b').length];
                        })()
                        """, "select '<img src=x onerror=alert(1)>' from <b>x</b>"))
                .isEqualTo(List.of("select '<img src=x onerror=alert(1)>' from <b>x</b>", 0));
    }

    /** Each literal is tokenized on its own, so a '?', a quote or '--' inside it stays part of it. */
    @Test
    void substituteReplacesPlaceholdersInOrderWithTheLiteralsOwnTokens() {
        assertThat(importModule("shared/sql.js", """
                        (() => {
                            const tokens = m.substitute(m.tokenize('a = ? and b = ?'), ["'it''s ? -- x'", '42']);
                            return [m.textOf(tokens), tokens.filter(t => t.type !== 'whitespace').map(t => t.type + ' ' + t.text)];
                        })()
                        """))
                .isEqualTo(List.of(
                        "a = 'it''s ? -- x' and b = 42",
                        List.of(
                                "identifier a",
                                "operator =",
                                "string 'it''s ? -- x'",
                                "keyword and",
                                "identifier b",
                                "operator =",
                                "number 42")));
    }

    /**
     * SqlLiterals truncates a huge bind value to a literal plus a trailing block comment
     * ("'xxxx' /* 7 characters omitted *\/"). Substitution must not split that pair across two
     * placeholders: it is one literal's tokens, and the next placeholder still gets its own value.
     */
    @Test
    void substituteKeepsATruncatedLiteralIntactAndStillAdvancesToTheNextPlaceholder() {
        assertThat(importModule("shared/sql.js", """
                        (() => {
                            const tokens = m.substitute(m.tokenize('a = ? and b = ?'),
                                ["'xxxx' /* 7 characters omitted */", "X'ab12' /* 7 bytes omitted */"]);
                            return [m.textOf(tokens), tokens.filter(t => t.type !== 'whitespace').map(t => t.type + ' ' + t.text)];
                        })()
                        """))
                .isEqualTo(List.of(
                        "a = 'xxxx' /* 7 characters omitted */ and b = X'ab12' /* 7 bytes omitted */",
                        List.of(
                                "identifier a",
                                "operator =",
                                "string 'xxxx'",
                                "comment /* 7 characters omitted */",
                                "keyword and",
                                "identifier b",
                                "operator =",
                                "identifier X",
                                "string 'ab12'",
                                "comment /* 7 bytes omitted */")));
    }

    @Test
    void substituteLeavesPlaceholdersBeyondTheLiteralsInPlace() {
        assertThat(importModule("shared/sql.js", "m.textOf(m.substitute(m.tokenize('? ?'), ['NULL']))"))
                .isEqualTo("NULL ?");
    }
}
