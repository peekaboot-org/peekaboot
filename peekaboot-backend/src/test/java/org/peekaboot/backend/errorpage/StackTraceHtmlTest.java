package org.peekaboot.backend.errorpage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The trace is rendered as Throwable.printStackTrace writes it - causes, suppressed
 * exceptions and "... N more" intact - with each frame classified, so a developer's eye
 * lands on their own code instead of forty framework frames.
 */
class StackTraceHtmlTest {

    private static final String TRACE = """
            java.lang.IllegalStateException: gateway unreachable
            \tat com.example.orders.OrderService.reconcile(OrderService.java:42)
            \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)
            \tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1006)
            Caused by: java.net.ConnectException: Connection refused
            \tat com.example.orders.Gateway.call(Gateway.java:17)
            \t... 12 more
            """;

    private static final List<String> EXCLUDED = List.of("org.springframework");

    @Test
    void marksFramesInTheApplicationsOwnPackages() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), List.of(), false);

        assertThat(html)
                .contains("<span class=\"pk-error__frame pk-error__frame--app\">"
                        + "\tat com.example.orders.OrderService.reconcile(OrderService.java:42)</span>");
    }

    @Test
    void leavesEveryOtherFrameMuted() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), List.of(), false);

        assertThat(html)
                .contains(
                        "<span class=\"pk-error__frame\">"
                                + "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)</span>");
    }

    @Test
    void keepsTheCauseChainAndItsElidedFrameCount() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), List.of(), false);

        assertThat(html)
                .contains("Caused by: java.net.ConnectException: Connection refused")
                .contains("... 12 more");
    }

    /**
     * A frame carries the name of the class loader that loaded it, then {@code //}, whenever
     * that loader has one - which Boot's own launcher and the devtools restart loader both do.
     */
    @Test
    void marksAnApplicationFrameBehindItsClassLoaderName() {
        String html = StackTraceHtml.render(
                "\tat app//com.example.orders.Gateway.call(Gateway.java:17)", List.of("com.example"), List.of(), false);

        assertThat(html).contains("pk-error__frame--app");
    }

    /** An exception message carries whatever the request carried. */
    @Test
    void escapesMarkupInTheTrace() {
        String html = StackTraceHtml.render(
                "java.lang.IllegalStateException: <script>alert(1)</script>", List.of(), List.of(), false);

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    /**
     * HtmlUtils.htmlEscape leaves "{{" alone, but PeekabootErrorView substitutes
     * {@code {{REVEAL_SCRIPT_TAGS}}} against a page that already carries this trace, so a
     * message spelling that placeholder must not survive into the markup unneutralised.
     */
    @Test
    void neutralisesAPlaceholderLookingMessageInTheTrace() {
        String html = StackTraceHtml.render(
                "java.lang.IllegalStateException: {{REVEAL_SCRIPT_TAGS}}", List.of(), List.of(), false);

        assertThat(html).doesNotContain("{{REVEAL_SCRIPT_TAGS}}").contains("&#123;&#123;REVEAL_SCRIPT_TAGS}}");
    }

    /** No packages registered - a plain context - classifies nothing rather than everything. */
    @Test
    void marksNoFrameWithoutApplicationPackages() {
        String html = StackTraceHtml.render(TRACE, List.of(), List.of(), false);

        assertThat(html).doesNotContain("pk-error__frame--app");
    }

    @Test
    void wrapsAHiddenRunInADisclosureNamingItsSize() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), EXCLUDED, true);

        assertThat(html)
                .contains("<details class=\"pk-error__hidden\">")
                .contains("<summary class=\"pk-error__hidden-summary\">2 frames hidden</summary>");
    }

    /** Hidden frames stay in the document so a reader can open them, and so ErrorPageIT can count them. */
    @Test
    void keepsHiddenFramesInTheMarkup() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), EXCLUDED, true);

        assertThat(html).contains("DispatcherServlet.doDispatch(DispatcherServlet.java:1089)");
    }

    @Test
    void rendersEveryFrameInlineWithFoldingOff() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), EXCLUDED, false);

        assertThat(html).doesNotContain("<details").doesNotContain("pk-error__hidden");
    }

    /**
     * A {@code contains} assertion cannot see a frame landing in the wrong place or a run's
     * frames coming out reversed - both still satisfy every fragment this class checks
     * elsewhere. Built by hand from {@link #TRACE} rather than pasted from the renderer's own
     * output, so it pins the intended markup rather than whatever bug produced it.
     */
    @Test
    void rendersTheWholeTraceWithTheHiddenRunInPlaceAndInOrder() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), EXCLUDED, true);

        assertThat(html)
                .isEqualTo(
                        "<span class=\"pk-error__frame\">java.lang.IllegalStateException: gateway unreachable</span>\n"
                                + "<span class=\"pk-error__frame pk-error__frame--app\">"
                                + "\tat com.example.orders.OrderService.reconcile(OrderService.java:42)</span>\n"
                                + "<details class=\"pk-error__hidden\">"
                                + "<summary class=\"pk-error__hidden-summary\">2 frames hidden</summary>"
                                + "<span class=\"pk-error__frame\">"
                                + "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)</span>\n"
                                + "<span class=\"pk-error__frame\">"
                                + "\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1006)</span>"
                                + "</details>\n"
                                + "<span class=\"pk-error__frame\">Caused by: java.net.ConnectException: Connection refused</span>\n"
                                + "<span class=\"pk-error__frame pk-error__frame--app\">"
                                + "\tat com.example.orders.Gateway.call(Gateway.java:17)</span>\n"
                                + "<span class=\"pk-error__frame\">\t... 12 more</span>");
    }

    /** The fold-off counterpart to {@link #rendersTheWholeTraceWithTheHiddenRunInPlaceAndInOrder()}. */
    @Test
    void rendersTheWholeTraceInlineAndInOrderWithFoldingOff() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), EXCLUDED, false);

        assertThat(html)
                .isEqualTo(
                        "<span class=\"pk-error__frame\">java.lang.IllegalStateException: gateway unreachable</span>\n"
                                + "<span class=\"pk-error__frame pk-error__frame--app\">"
                                + "\tat com.example.orders.OrderService.reconcile(OrderService.java:42)</span>\n"
                                + "<span class=\"pk-error__frame\">"
                                + "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)</span>\n"
                                + "<span class=\"pk-error__frame\">"
                                + "\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1006)</span>\n"
                                + "<span class=\"pk-error__frame\">Caused by: java.net.ConnectException: Connection refused</span>\n"
                                + "<span class=\"pk-error__frame pk-error__frame--app\">"
                                + "\tat com.example.orders.Gateway.call(Gateway.java:17)</span>\n"
                                + "<span class=\"pk-error__frame\">\t... 12 more</span>");
    }

    @Test
    void neverFoldsTheApplicationsOwnFrame() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"), List.of("com.example"), true);

        assertThat(html).doesNotContain("<details");
    }

    /** One frame reads better than "1 frames". */
    @Test
    void namesASingleHiddenFrameInTheSingular() {
        String html = StackTraceHtml.render("\tat org.springframework.A.a(A.java:1)", List.of(), EXCLUDED, true);

        assertThat(html).contains(">1 frame hidden<");
    }

    @Test
    void namesSeveralHiddenFramesInThePlural() {
        String html = StackTraceHtml.render(
                "\tat org.springframework.A.a(A.java:1)\n\tat org.springframework.B.b(B.java:2)",
                List.of(),
                EXCLUDED,
                true);

        assertThat(html).contains(">2 frames hidden<");
    }
}
