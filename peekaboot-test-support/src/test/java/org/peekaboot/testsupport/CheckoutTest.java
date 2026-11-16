package org.peekaboot.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckoutTest {

    @Test
    void findsTheNearestDirectoryAboveTheStartThatHoldsTheEntry(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve(".mvn"));
        Path module = Files.createDirectory(root.resolve("module"));
        Files.createFile(module.resolve("pom.xml"));
        Path classes = Files.createDirectories(module.resolve("build/classes/java/test"));

        assertThat(Checkout.ancestorHolding(classes, ".mvn")).isEqualTo(root);
        assertThat(Checkout.ancestorHolding(classes, "pom.xml")).isEqualTo(module);
    }

    @Test
    void startsAtTheAnchorsOwnClassesNotAtTheWorkingDirectory() {
        assertThat(Checkout.ancestorHolding(CheckoutTest.class, "pom.xml").getFileName())
                .hasToString("peekaboot-test-support");
        // Both builds run a test from its module, so only an entry below the module tells the two starts apart.
        assertThatCode(() ->
                        Checkout.ancestorHolding(CheckoutTest.class, "org/peekaboot/testsupport/CheckoutTest.class"))
                .doesNotThrowAnyException();
    }

    @Test
    void failsNamingTheEntryAndWhereItLooked(@TempDir Path root) {
        String entry = "no-such-entry-" + UUID.randomUUID();

        assertThatThrownBy(() -> Checkout.ancestorHolding(root, entry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(entry)
                .hasMessageContaining(root.toString());
    }
}
