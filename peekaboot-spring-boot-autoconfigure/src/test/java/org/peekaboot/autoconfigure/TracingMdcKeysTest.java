package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.capture.MdcKeys;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class TracingMdcKeysTest {

    private final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    @Test
    void defaultsWhenTheAppNamesNoKeys() {
        assertThat(TracingMdcKeys.of(new MockEnvironment(), classLoader)).isEqualTo(MdcKeys.DEFAULTS);
    }

    @Test
    void readsTheKeysTheAppRenamed() {
        MockEnvironment environment = renamedKeys();

        assertThat(TracingMdcKeys.of(environment, classLoader)).isEqualTo(new MdcKeys("tid", "sid"));
    }

    @Test
    void readsARenamedKeyFromItsEnvironmentVariable() {
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "systemEnvironment", Map.of("MANAGEMENT_TRACING_MDC_TRACEIDKEY", "tid")));

        assertThat(TracingMdcKeys.of(environment, classLoader)).isEqualTo(new MdcKeys("tid", "spanId"));
    }

    @Test
    void keepsTheDefaultsWhereBootHasNoMdcKeySetting() {
        ClassLoader withoutMdcSettings = new FilteredClassLoader(name -> name.equals(TracingMdcKeys.BOOT_MDC_SETTINGS));

        assertThat(TracingMdcKeys.of(renamedKeys(), withoutMdcSettings)).isEqualTo(MdcKeys.DEFAULTS);
    }

    private static MockEnvironment renamedKeys() {
        return new MockEnvironment()
                .withProperty("management.tracing.mdc.trace-id-key", "tid")
                .withProperty("management.tracing.mdc.span-id-key", "sid");
    }
}
