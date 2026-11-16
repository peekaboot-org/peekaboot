package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The reactor's test sources, for the tests that hold them to docs/TESTING.md. */
final class TestSources {

    // As literals the calls would make this file a writer in its own scan.
    private static final Pattern JVM_DEFAULT_WRITE = Pattern.compile("(Locale|TimeZone)\\.setDefault\\("
            + "|Thread\\.setDefaultUncaughtExceptionHandler\\("
            + "|System\\.(setProperty|clearProperty|setOut|setErr|setIn)\\(");

    private TestSources() {}

    /** Every Java file under a module's {@code src/test/java}, in path order. */
    static List<Path> all() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(BuildFiles.reactorRoot(), "peekaboot-*")) {
            for (Path module : modules) {
                Path tests = module.resolve("src/test/java");
                if (Files.isDirectory(tests)) {
                    try (Stream<Path> files = Files.walk(tests)) {
                        files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
                    }
                }
            }
        }
        return sources.stream().sorted().toList();
    }

    /**
     * Whether {@code source} calls a setter of a JVM-wide default (locale, time zone, uncaught
     * exception handler, system property, standard stream) without being {@code @Isolated}. It
     * reads the text of one file, so a nested class is covered by its outer class's annotation.
     */
    static boolean writesAJvmDefaultUnisolated(String source) {
        List<String> code = source.lines()
                .map(String::strip)
                .filter(line -> !isComment(line))
                .toList();
        return code.stream().anyMatch(line -> JVM_DEFAULT_WRITE.matcher(line).find())
                && code.stream().noneMatch(line -> line.contains("@Isolated"));
    }

    private static boolean isComment(String strippedLine) {
        return strippedLine.startsWith("*") || strippedLine.startsWith("/*") || strippedLine.startsWith("//");
    }
}
