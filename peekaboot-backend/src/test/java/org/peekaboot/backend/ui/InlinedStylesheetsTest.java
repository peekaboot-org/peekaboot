package org.peekaboot.backend.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The inlined copy is what keeps a server-rendered surface styled where an authorization
 * gate refuses everything under {@code /peekaboot/**}; the link elements are what keep it
 * styled where a Content-Security-Policy drops inline styles. Both come from one file.
 */
class InlinedStylesheetsTest {

    private final InlinedStylesheets stylesheets = InlinedStylesheets.of(
            List.of("/ui/assets/tokens.css", "/ui/assets/base.css"), List.of("/ui/assets/tokens.css"));

    @Test
    void inlinesTheSheetsItIsAskedToInline() {
        assertThat(stylesheets.css()).contains("--pk-bg:");
    }

    @Test
    void linksEverySheetItIsAskedToLink() {
        assertThat(stylesheets.links())
                .contains("<link rel=\"stylesheet\" href=\"{{BASE}}/ui/assets/tokens.css\">")
                .contains("<link rel=\"stylesheet\" href=\"{{BASE}}/ui/assets/base.css\">");
    }

    /** A relative url() resolves against its stylesheet; inlined into a page it would resolve against the page. */
    @Test
    void rewritesRelativeUrlsToTheServedPathOfTheirSheet() {
        assertThat(stylesheets.css()).contains("url('{{BASE}}/ui/vendor/geist/").doesNotContain("url('../vendor/");
    }

    /** The sheets carry their design rationale in comments; a host page need not download it. */
    @Test
    void stripsComments() {
        assertThat(stylesheets.css()).doesNotContain("/*");
    }

    /** peekaboot-frontend absent means the whole UI is absent; an unstyled surface beats a failure. */
    @Test
    void aMissingSheetLeavesTheOthersInPlace() {
        InlinedStylesheets withMissing =
                InlinedStylesheets.of(List.of(), List.of("/ui/assets/nope.css", "/ui/assets/tokens.css"));

        assertThat(withMissing.css()).contains("--pk-bg:");
    }
}
