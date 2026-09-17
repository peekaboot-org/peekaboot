package org.peekaboot.backend.stacktrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Which lines of a stack trace a reader is spared, and which are the application's own. The two
 * answer nearly the same question from opposite ends and are deliberately separate: an
 * application frame is what the reader came for, so it is never hidden.
 *
 * <p>Indices are line numbers into the trace as {@code Throwable.printStackTrace} wrote it.
 */
public final class StackTraceFolding {

    /** How printStackTrace writes a frame; anything else is a header, a cause or an elision. */
    private static final String FRAME_PREFIX = "\tat ";

    private StackTraceFolding() {}

    /** Half-open, so {@code endExclusive - start} is the number of lines. */
    public record Range(int start, int endExclusive) {}

    /**
     * {@code lines} is exactly {@code trace.lines().toList()}, the list the ranges index into. A
     * caller that sends it back joined with {@code "\n"} guarantees the browser's own {@code
     * split('\n')} agrees with these indices, rather than trusting a second language to split a
     * trace the same way {@link String#lines()} does.
     *
     * <p>{@code hidden} is ascending, disjoint, and every range is non-empty - {@code
     * endExclusive > start} always holds, since a consumer that walks the ranges in line order
     * and jumps straight to a match's {@code endExclusive} would sit at the same index forever
     * on an empty one. A consumer relies on that order: it walks {@code hidden} once, front to
     * back, alongside {@code lines}, rather than searching it for the range starting at each
     * index.
     */
    public record FoldedTrace(List<String> lines, List<Range> hidden, List<Range> applicationFrames) {}

    public static FoldedTrace fold(String trace, List<String> exclusions, List<String> applicationPackages) {
        List<String> lines = trace.lines().toList();
        List<Range> hidden = new ArrayList<>();
        List<Range> application = new ArrayList<>();
        int hiddenRunStart = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean applicationFrame = isFrame(line) && isApplicationFrame(line, applicationPackages);
            if (applicationFrame) {
                application.add(new Range(i, i + 1));
            }
            if (isFrame(line) && !applicationFrame && matchesAny(line, exclusions)) {
                hiddenRunStart = hiddenRunStart < 0 ? i : hiddenRunStart;
            } else if (hiddenRunStart >= 0) {
                hidden.add(new Range(hiddenRunStart, i));
                hiddenRunStart = -1;
            }
        }
        if (hiddenRunStart >= 0) {
            hidden.add(new Range(hiddenRunStart, lines.size()));
        }
        return new FoldedTrace(lines, List.copyOf(hidden), List.copyOf(application));
    }

    private static boolean isFrame(String line) {
        return line.startsWith(FRAME_PREFIX);
    }

    private static boolean matchesAny(String line, List<String> exclusions) {
        return exclusions.stream().anyMatch(line::contains);
    }

    private static boolean isApplicationFrame(String line, List<String> applicationPackages) {
        String className = className(line.substring(FRAME_PREFIX.length()));
        return applicationPackages.stream().anyMatch(each -> className.startsWith(each + "."));
    }

    /**
     * A class loader or module name comes before the class name and ends in a slash — {@code
     * app//}, {@code java.base/}, {@code loader//module@1.0/} — and a class name never contains
     * one, so the last slash is always the boundary.
     */
    private static String className(String frame) {
        int lastSlash = frame.lastIndexOf('/');
        return lastSlash < 0 ? frame : frame.substring(lastSlash + 1);
    }
}
