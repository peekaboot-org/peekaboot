package org.peekaboot.backend.stacktrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;

/**
 * Which lines a reader is spared. Consecutive excluded frames collapse into one run so the
 * shape of the trace survives the folding.
 */
class StackTraceFoldingTest {

    private static final String TRACE = """
            java.lang.IllegalStateException: gateway unreachable
            \tat com.example.orders.OrderService.reconcile(OrderService.java:42)
            \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)
            \tat org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:197)
            Caused by: java.net.ConnectException: Connection refused
            \tat com.example.orders.Gateway.call(Gateway.java:17)
            \t... 12 more
            """;

    private static final List<String> EXCLUSIONS = List.of("org.springframework", "org.apache.catalina");
    private static final List<String> APP = List.of("com.example");

    @Test
    void collapsesConsecutiveExcludedFramesIntoOneRun() {
        FoldedTrace folded = StackTraceFolding.fold(TRACE, EXCLUSIONS, APP);

        assertThat(folded.hidden()).containsExactly(new StackTraceFolding.Range(2, 4));
    }

    @Test
    void marksTheApplicationsOwnFrames() {
        FoldedTrace folded = StackTraceFolding.fold(TRACE, EXCLUSIONS, APP);

        assertThat(folded.applicationFrames())
                .containsExactly(new StackTraceFolding.Range(1, 2), new StackTraceFolding.Range(5, 6));
    }

    /** The header, the cause and the elision are the trace's structure, not frames to hide. */
    @Test
    void neverHidesALineThatIsNotAFrame() {
        FoldedTrace folded = StackTraceFolding.fold(TRACE, List.of("java.lang", "Caused by", "more"), List.of());

        assertThat(folded.hidden()).isEmpty();
    }

    /** The application's own frame is the reason the page exists. */
    @Test
    void neverHidesAnApplicationFrameEvenWhenAPatternMatchesIt() {
        FoldedTrace folded = StackTraceFolding.fold(TRACE, List.of("com.example"), APP);

        assertThat(folded.hidden()).isEmpty();
        assertThat(folded.applicationFrames()).hasSize(2);
    }

    /** Logback matches a substring of the whole line, which is why a marker like ByCGLIB works. */
    @Test
    void matchesAnywhereInTheLineRatherThanAsAPrefix() {
        FoldedTrace folded = StackTraceFolding.fold(
                "\tat com.example.Foo$$EnhancerByCGLIB$$abc.bar(Foo.java:1)", List.of("ByCGLIB"), List.of());

        assertThat(folded.hidden()).containsExactly(new StackTraceFolding.Range(0, 1));
    }

    /** A frame names its class loader before the class whenever that loader has one. */
    @Test
    void marksAnApplicationFrameBehindItsClassLoaderName() {
        FoldedTrace folded =
                StackTraceFolding.fold("\tat app//com.example.Gateway.call(Gateway.java:17)", List.of(), APP);

        assertThat(folded.applicationFrames()).containsExactly(new StackTraceFolding.Range(0, 1));
    }

    @Test
    void hidesNothingWithoutExclusions() {
        assertThat(StackTraceFolding.fold(TRACE, List.of(), APP).hidden()).isEmpty();
    }

    @Test
    void handlesAnEmptyTrace() {
        FoldedTrace folded = StackTraceFolding.fold("", EXCLUSIONS, APP);

        assertThat(folded.hidden()).isEmpty();
        assertThat(folded.applicationFrames()).isEmpty();
    }

    /** A modularized application names its module before the class, with a single slash. */
    @Test
    void marksAnApplicationFrameBehindItsModuleName() {
        FoldedTrace folded = StackTraceFolding.fold(
                "\tat orders.app/com.example.orders.OrderService.reconcile(OrderService.java:42)",
                List.of("orders.app"),
                APP);

        assertThat(folded.applicationFrames()).containsExactly(new StackTraceFolding.Range(0, 1));
        assertThat(folded.hidden()).isEmpty();
    }

    @Test
    void emitsOneRunPerGroupOfExcludedFrames() {
        FoldedTrace folded = StackTraceFolding.fold("""
                \tat org.springframework.A.a(A.java:1)
                \tat com.example.Keep.keep(Keep.java:2)
                \tat org.springframework.B.b(B.java:3)
                \tat org.springframework.C.c(C.java:4)""", List.of("org.springframework"), APP);

        assertThat(folded.hidden())
                .containsExactly(new StackTraceFolding.Range(0, 1), new StackTraceFolding.Range(2, 4));
    }

    /** A cause line is the trace's structure, so it ends the run it falls inside. */
    @Test
    void aNonFrameLineSplitsAHiddenRun() {
        FoldedTrace folded = StackTraceFolding.fold("""
                \tat org.springframework.A.a(A.java:1)
                Caused by: java.net.ConnectException: Connection refused
                \tat org.springframework.B.b(B.java:3)""", List.of("org.springframework"), APP);

        assertThat(folded.hidden())
                .containsExactly(new StackTraceFolding.Range(0, 1), new StackTraceFolding.Range(2, 3));
    }

    @Test
    void anApplicationFrameSplitsAHiddenRun() {
        FoldedTrace folded = StackTraceFolding.fold("""
                \tat org.springframework.A.a(A.java:1)
                \tat com.example.Keep.keep(Keep.java:2)
                \tat org.springframework.B.b(B.java:3)""", List.of("org.springframework"), APP);

        assertThat(folded.hidden())
                .containsExactly(new StackTraceFolding.Range(0, 1), new StackTraceFolding.Range(2, 3));
    }

    /** The ranges index these lines, so a caller that sends them cannot disagree about where a line ends. */
    @Test
    void reportsTheLinesTheRangesIndex() {
        FoldedTrace folded = StackTraceFolding.fold("a\rb\nc", List.of(), List.of());

        assertThat(folded.lines()).containsExactly("a", "b", "c");
    }
}
