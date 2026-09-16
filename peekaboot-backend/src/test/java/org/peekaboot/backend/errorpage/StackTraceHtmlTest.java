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
            Caused by: java.net.ConnectException: Connection refused
            \tat com.example.orders.Gateway.call(Gateway.java:17)
            \t... 12 more
            """;

    @Test
    void marksFramesInTheApplicationsOwnPackages() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"));

        assertThat(html)
                .contains("<span class=\"pk-error__frame pk-error__frame--app\">"
                        + "\tat com.example.orders.OrderService.reconcile(OrderService.java:42)</span>");
    }

    @Test
    void leavesEveryOtherFrameMuted() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"));

        assertThat(html)
                .contains(
                        "<span class=\"pk-error__frame\">"
                                + "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)</span>");
    }

    @Test
    void keepsTheCauseChainAndItsElidedFrameCount() {
        String html = StackTraceHtml.render(TRACE, List.of("com.example"));

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
                "\tat app//com.example.orders.Gateway.call(Gateway.java:17)", List.of("com.example"));

        assertThat(html).contains("pk-error__frame--app");
    }

    /** An exception message carries whatever the request carried. */
    @Test
    void escapesMarkupInTheTrace() {
        String html = StackTraceHtml.render("java.lang.IllegalStateException: <script>alert(1)</script>", List.of());

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    /** No packages registered - a plain context - classifies nothing rather than everything. */
    @Test
    void marksNoFrameWithoutApplicationPackages() {
        String html = StackTraceHtml.render(TRACE, List.of());

        assertThat(html).doesNotContain("pk-error__frame--app");
    }
}
