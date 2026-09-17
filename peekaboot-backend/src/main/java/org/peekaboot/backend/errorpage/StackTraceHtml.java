package org.peekaboot.backend.errorpage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;
import org.peekaboot.backend.stacktrace.StackTraceFolding.Range;
import org.springframework.web.util.HtmlUtils;

/**
 * A stack trace as markup: every line of it, escaped, each one carrying whether it is a frame
 * in the application's own code. A trace is forty framework frames around the two that
 * matter, and the page exists so a developer finds those two without reading the rest - the
 * runs the folding hid go behind a disclosure.
 */
final class StackTraceHtml {

    private static final String FRAME = "pk-error__frame";

    private static final String APPLICATION_FRAME = FRAME + " " + FRAME + "--app";

    private StackTraceHtml() {}

    /** The trace as printStackTrace wrote it, application frames marked and hidden runs behind a disclosure. */
    static String render(FoldedTrace folded) {
        List<String> lines = folded.lines();
        Set<Integer> applicationFrames = expand(folded.applicationFrames());
        Deque<Range> hidden = new ArrayDeque<>(folded.hidden());

        StringBuilder html = new StringBuilder();
        for (int i = 0; i < lines.size(); ) {
            if (!hidden.isEmpty() && hidden.peek().start() == i) {
                Range run = hidden.poll();
                html.append(disclosure(lines, run, applicationFrames));
                i = run.endExclusive();
            } else {
                html.append(span(lines.get(i), applicationFrames.contains(i))).append('\n');
                i++;
            }
        }
        return html.toString().stripTrailing();
    }

    private static String disclosure(List<String> lines, Range run, Set<Integer> applicationFrames) {
        int count = run.endExclusive() - run.start();
        StringBuilder frames = new StringBuilder();
        for (int i = run.start(); i < run.endExclusive(); i++) {
            frames.append(span(lines.get(i), applicationFrames.contains(i))).append('\n');
        }
        return "<details class=\"pk-error__hidden\"><summary class=\"pk-error__hidden-summary\">"
                + count + (count == 1 ? " frame" : " frames") + " hidden</summary>"
                + frames.toString().stripTrailing() + "</details>\n";
    }

    private static String span(String line, boolean applicationFrame) {
        return "<span class=\"" + (applicationFrame ? APPLICATION_FRAME : FRAME) + "\">" + escape(line) + "</span>";
    }

    /**
     * {@link HtmlUtils#htmlEscape} leaves a double brace alone, but the line traveling through
     * here is not safe against it: the trace's own first line repeats the exception's message,
     * and {@code PeekabootErrorView.detail} fills the frames in before substituting its own
     * {@code {{REVEAL_SCRIPT_TAGS}}} placeholder last of all - so a message spelling that
     * placeholder would otherwise reopen it against the already-built page. Neutralised the
     * same way {@code PeekabootErrorView.escape} neutralises the message on its other path
     * onto the page.
     */
    private static String escape(String line) {
        return HtmlUtils.htmlEscape(line).replace("{{", "&#123;&#123;");
    }

    private static Set<Integer> expand(List<Range> ranges) {
        Set<Integer> indexes = new HashSet<>();
        ranges.forEach(range -> {
            for (int i = range.start(); i < range.endExclusive(); i++) {
                indexes.add(i);
            }
        });
        return indexes;
    }
}
