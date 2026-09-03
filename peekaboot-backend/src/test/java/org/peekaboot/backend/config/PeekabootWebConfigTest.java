package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.scheduledtasks.ScheduledTaskInfo;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

class PeekabootWebConfigTest {

    /**
     * The converters Spring MVC ends up with, in the order it consults them: the defaults
     * an application starts from, plus whatever {@link PeekabootWebConfig} contributes.
     */
    private static List<HttpMessageConverter<?>> registeredConverters() {
        HttpMessageConverters.ServerBuilder builder =
                HttpMessageConverters.forServer().registerDefaults();
        new PeekabootWebConfig().configureMessageConverters(builder);
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        builder.build().forEach(converters::add);
        return converters;
    }

    /** Spring MVC writes a return value with the first converter that accepts it. */
    private static HttpMessageConverter<?> firstWriterOf(Class<?> valueType) {
        return registeredConverters().stream()
                .filter(converter -> converter.canWrite(valueType, MediaType.APPLICATION_JSON))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void peekabootsOwnMapperWritesPeekabootTypes() {
        assertThat(firstWriterOf(ScheduledTaskInfo.class)).isInstanceOf(PeekabootJsonMessageConverter.class);
    }

    @Test
    void theApplicationsOwnConvertersWriteEverythingElse() {
        assertThat(firstWriterOf(Map.class))
                .isNotInstanceOf(PeekabootJsonMessageConverter.class)
                .isInstanceOf(JacksonJsonHttpMessageConverter.class);
    }
}
