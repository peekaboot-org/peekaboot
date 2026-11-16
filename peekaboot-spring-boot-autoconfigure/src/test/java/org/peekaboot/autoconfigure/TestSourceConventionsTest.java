package org.peekaboot.autoconfigure;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.autoconfigure.BuildFiles.reactorRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A test that writes a JVM-wide default changes it for every class running beside it, so its
 * class is {@code @Isolated}. A module that runs its classes one at a time never shows a missing
 * annotation, so this scan of every module's test sources does.
 */
class TestSourceConventionsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Locale.setDefault(Locale.ROOT)",
                "TimeZone.setDefault(zone)",
                "Thread.setDefaultUncaughtExceptionHandler(handler)",
                "System.setProperty(\"a\", \"b\")",
                "System.clearProperty(\"a\")",
                "System.setOut(out)",
                "System.setErr(err)",
                "System.setIn(in)"
            })
    void anUnisolatedWriteOfAJvmDefaultIsFlagged(String call) {
        assertThat(TestSources.writesAJvmDefaultUnisolated(writer(call))).isTrue();
    }

    @Test
    void anIsolatedWriterIsNotFlagged() {
        String source = "@Isolated(\"sets the default locale\")\n" + writer("Locale.setDefault(Locale.ROOT)");

        assertThat(TestSources.writesAJvmDefaultUnisolated(source)).isFalse();
    }

    @Test
    void aCallNamedOnlyInACommentIsNotFlagged() {
        String source = """
                /**
                 * Locale.setDefault(Locale.GERMANY) is JVM-global
                 */
                class X {
                    void t() {
                        // TimeZone.setDefault(zone)
                    }
                }
                """;

        assertThat(TestSources.writesAJvmDefaultUnisolated(source)).isFalse();
    }

    @Test
    void aWriterInANestedClassIsFlaggedThroughItsFile() {
        String source = """
                class X {
                    void t() {
                        new Handler();
                    }

                    static final class Handler {
                        Handler() {
                            Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {});
                        }
                    }
                }
                """;

        assertThat(TestSources.writesAJvmDefaultUnisolated(source)).isTrue();
    }

    @Test
    void everyTestSourceThatWritesAJvmDefaultIsIsolated() throws IOException {
        Path root = reactorRoot();
        List<Path> sources = TestSources.all();
        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            // This class's literals are the calls.
            if (!source.endsWith("TestSourceConventionsTest.java")
                    && TestSources.writesAJvmDefaultUnisolated(Files.readString(source))) {
                offenders.add(root.relativize(source).toString());
            }
        }

        Set<String> modules = sources.stream()
                .map(source -> root.relativize(source).getName(0).toString())
                .collect(toSet());
        assertThat(modules)
                .as("modules whose test sources the scan reached")
                .contains("peekaboot-backend", "peekaboot-testing-app");
        assertThat(offenders)
                .as("test sources that write a JVM-wide default and are not @Isolated; see docs/TESTING.md")
                .isEmpty();
    }

    private static String writer(String call) {
        return "class X {\n    void t() {\n        " + call + ";\n    }\n}\n";
    }
}
