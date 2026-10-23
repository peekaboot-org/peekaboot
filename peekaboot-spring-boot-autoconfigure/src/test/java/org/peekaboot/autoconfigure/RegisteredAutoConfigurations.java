package org.peekaboot.autoconfigure;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.util.ClassUtils;

/** Every Peekaboot auto-configuration the {@code .imports} file registers, for the tests that check them all at once. */
final class RegisteredAutoConfigurations {

    private RegisteredAutoConfigurations() {}

    static List<Class<?>> classes() {
        ClassLoader classLoader = RegisteredAutoConfigurations.class.getClassLoader();
        return ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates().stream()
                .filter(name -> name.startsWith("org.peekaboot."))
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, classLoader))
                .toList();
    }
}
