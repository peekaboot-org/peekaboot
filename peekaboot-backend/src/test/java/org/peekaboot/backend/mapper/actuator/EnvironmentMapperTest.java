package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.actuator.parsed.EnvResponse;
import org.peekaboot.backend.domain.environment.EnvironmentInfo;
import org.peekaboot.backend.domain.property.Property;
import org.peekaboot.backend.masking.MaskingEngine;

class EnvironmentMapperTest {

    private final EnvironmentMapper mapper = new EnvironmentMapper(new MaskingEngine());

    @Test
    void mapExtractsActiveProfiles() {
        EnvResponse env = new EnvResponse(List.of("dev", "local"), List.of());
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.activeProfiles()).containsExactly("dev", "local");
    }

    /** The endpoint types values as it finds them (a YAML port is an Integer); the dashboard shows text. */
    @Test
    void mapExtractsPropertySources() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("server.port", new EnvResponse.PropertyValueDescriptor(8080)))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources()).hasSize(1);
        assertThat(result.propertySources().get(0).name()).isEqualTo("application.properties");
        assertThat(result.propertySources().get(0).properties())
                .extracting(Property::key, Property::value)
                .containsExactly(tuple("server.port", "8080"));
    }

    @Test
    void mapHandlesNullInput() {
        EnvironmentInfo result = mapper.map(null, false);
        assertThat(result.activeProfiles()).isEmpty();
        assertThat(result.propertySources()).isEmpty();
    }

    @Test
    void mapHandlesEmptyProfiles() {
        EnvResponse env = new EnvResponse(List.of(), List.of());
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.activeProfiles()).isEmpty();
    }

    @Test
    void mapFallsBackToUnknownNameWhenPropertySourceNameIsNull() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        null, Map.of("server.port", new EnvResponse.PropertyValueDescriptor("8080")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources()).hasSize(1);
        assertThat(result.propertySources().get(0).name()).isEqualTo("unknown");
    }

    @Test
    void mapReturnsNullValueWhenPropertyValueIsNull() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties", Map.of("some.flag", new EnvResponse.PropertyValueDescriptor(null)))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isNull();
    }

    @Test
    void mapMasksSensitiveKeyValue() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("spring.datasource.password", new EnvResponse.PropertyValueDescriptor("hunter2")))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo("******");
    }

    // A URL parameter inside a property value is judged as a connection param, so a run-together name masks.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "jdbc:postgresql://db/shop?sslpassword=S3Cx&sslmode=require"
                        + "|jdbc:postgresql://db/shop?sslpassword=******&sslmode=require",
                "jdbc:sqlserver://db:1433;databaseName=shop;keyVaultProviderClientKey=S3Cx"
                        + "|jdbc:sqlserver://db:1433;databaseName=shop;keyVaultProviderClientKey=******",
                "jdbc:sqlserver://db:1433;\tpassword=S3Cx|jdbc:sqlserver://db:1433;\tpassword=******",
                "jdbc:postgresql://db/shop?sslmode=require|jdbc:postgresql://db/shop?sslmode=require"
            })
    void mapMasksASecretUrlParameterInsideAPropertyValue(String url, String expected) {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("spring.datasource.url", new EnvResponse.PropertyValueDescriptor(url)))));
        EnvironmentInfo result = mapper.map(env, false);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo(expected);
    }

    @Test
    void mapReturnsAUrlValueVerbatimWhenUnmaskIsTrue() {
        String url = "jdbc:postgresql://db/shop?sslpassword=S3Cx";
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("spring.datasource.url", new EnvResponse.PropertyValueDescriptor(url)))));
        EnvironmentInfo result = mapper.map(env, true);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo(url);
    }

    @Test
    void mapReturnsRealValueWhenUnmaskIsTrue() {
        EnvResponse env = new EnvResponse(
                List.of(),
                List.of(new EnvResponse.PropertySource(
                        "application.properties",
                        Map.of("spring.datasource.password", new EnvResponse.PropertyValueDescriptor("hunter2")))));
        EnvironmentInfo result = mapper.map(env, true);
        assertThat(result.propertySources().get(0).properties().get(0).value()).isEqualTo("hunter2");
    }
}
