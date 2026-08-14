package org.peekaboot.backend.mapper.actuator;

import org.peekaboot.backend.actuator.raw.ConfigPropsResponse;
import org.peekaboot.backend.domain.config.ConfigGroup;
import org.peekaboot.backend.domain.config.ConfigInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigMapperTest {

    private final ConfigMapper mapper = new ConfigMapper();

    @Test
    void map_shouldGroupByPrefix() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(
            Map.of("application", new ConfigPropsResponse.ConfigContext(
                Map.of(
                    "spring.datasource-DataSourceProperties", new ConfigPropsResponse.ConfigBean(
                        "spring.datasource",
                        Map.of("url", "jdbc:h2:mem:test", "driverClassName", "org.h2.Driver"),
                        Map.of()
                    ),
                    "server-ServerProperties", new ConfigPropsResponse.ConfigBean(
                        "server",
                        Map.of("port", "8080"),
                        Map.of()
                    )
                ),
                null
            ))
        );

        ConfigInfo result = mapper.map(configprops);

        assertThat(result.groups()).hasSize(2);
        assertThat(result.groups())
                .extracting(ConfigGroup::prefix)
                .containsExactlyInAnyOrder("spring.datasource", "server");
    }

    @Test
    void map_shouldFallBackToUnknownPrefixWhenPrefixIsNull() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(
            Map.of("application", new ConfigPropsResponse.ConfigContext(
                Map.of("mystery-MysteryProperties", new ConfigPropsResponse.ConfigBean(
                    null,
                    Map.of("value", "1"),
                    Map.of()
                )),
                null
            ))
        );

        ConfigInfo result = mapper.map(configprops);

        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().get(0).prefix()).isEqualTo("unknown");
    }

    @Test
    void map_shouldMaskSensitiveProperties() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(
            Map.of("application", new ConfigPropsResponse.ConfigContext(
                Map.of("datasource", new ConfigPropsResponse.ConfigBean(
                    "spring.datasource",
                    Map.of("password", "secret123", "username", "admin"),
                    Map.of()
                )),
                null
            ))
        );

        ConfigInfo result = mapper.map(configprops);

        assertThat(result.groups().get(0).properties()).anyMatch(p -> p.key().equals("password") && p.value().equals("********"));
        assertThat(result.groups().get(0).properties()).anyMatch(p -> p.key().equals("username") && p.value().equals("admin"));
    }

    @Test
    void map_shouldHandleNullInput() {
        ConfigInfo result = mapper.map(null);
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void map_shouldHandleNullContexts() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(null);
        ConfigInfo result = mapper.map(configprops);
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void map_shouldMaskCredentialsKey() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(
            Map.of("application", new ConfigPropsResponse.ConfigContext(
                Map.of("aws", new ConfigPropsResponse.ConfigBean(
                    "aws",
                    Map.of("credentials", "AKIA..."),
                    Map.of()
                )),
                null
            ))
        );

        ConfigInfo result = mapper.map(configprops);
        assertThat(result.groups().get(0).properties().get(0).value()).isEqualTo("********");
    }
}
