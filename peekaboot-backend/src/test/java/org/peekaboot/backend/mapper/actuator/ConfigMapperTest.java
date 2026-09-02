package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.ConfigPropsResponse;
import org.peekaboot.backend.domain.config.ConfigGroup;
import org.peekaboot.backend.domain.config.ConfigInfo;
import org.peekaboot.backend.domain.config.ConfigProperty;
import org.peekaboot.backend.masking.MaskingEngine;

class ConfigMapperTest {

    private final ConfigMapper mapper = new ConfigMapper(new MaskingEngine());

    @Test
    void map_shouldGroupByPrefix() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of(
                                "spring.datasource-DataSourceProperties",
                                        new ConfigPropsResponse.ConfigBean(
                                                "spring.datasource",
                                                Map.of("url", "jdbc:h2:mem:test", "driverClassName", "org.h2.Driver")),
                                "server-ServerProperties",
                                        new ConfigPropsResponse.ConfigBean("server", Map.of("port", "8080"))),
                        null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups()).hasSize(2);
        assertThat(result.groups())
                .extracting(ConfigGroup::prefix)
                .containsExactlyInAnyOrder("spring.datasource", "server");
    }

    @Test
    void map_shouldFallBackToUnknownPrefixWhenPrefixIsNull() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of(
                                "mystery-MysteryProperties",
                                new ConfigPropsResponse.ConfigBean(null, Map.of("value", "1"))),
                        null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().get(0).prefix()).isEqualTo("unknown");
    }

    @Test
    void map_shouldMaskSensitiveProperties() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of(
                                "datasource",
                                new ConfigPropsResponse.ConfigBean(
                                        "spring.datasource", Map.of("password", "secret123", "username", "admin"))),
                        null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups().get(0).properties())
                .anyMatch(p -> p.key().equals("password") && p.value().equals("******"));
        assertThat(result.groups().get(0).properties())
                .anyMatch(p -> p.key().equals("username") && p.value().equals("admin"));
    }

    @Test
    void map_shouldHandleNullInput() {
        ConfigInfo result = mapper.map(null, false);
        assertThat(result.groups()).isEmpty();
    }

    @Test
    void map_shouldHandleNullContexts() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(null);
        ConfigInfo result = mapper.map(configprops, false);
        assertThat(result.groups()).isEmpty();
    }

    /**
     * A nested tree flattens to one dotted-key property per leaf, so the Config tab's
     * filter can match nested keys and values directly - and the sensitive leaf still
     * masks, by its leaf key, on the way.
     */
    @Test
    void map_shouldFlattenANestedTreeToDottedKeysAndMaskSensitiveLeaves() {
        Map<String, Object> google = new LinkedHashMap<>();
        google.put("clientId", "abc123");
        google.put("clientSecret", "GOCSPX-SuperSecretValue");
        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("google", google);

        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of(
                                "oauth2",
                                new ConfigPropsResponse.ConfigBean(
                                        "spring.security.oauth2.client", Map.of("registration", registration))),
                        null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups().get(0).properties())
                .extracting(ConfigProperty::key, ConfigProperty::value)
                .containsExactly(
                        tuple("registration.google.clientId", "abc123"),
                        tuple("registration.google.clientSecret", "******"));
    }

    /** A sensitive key masks its whole subtree, arriving as that one key rather than per-leaf. */
    @Test
    void map_shouldMaskAWholeSubtreeUnderASensitiveKey() {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("user", "admin");
        credentials.put("token", "tok-123");

        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of(
                                "gateway",
                                new ConfigPropsResponse.ConfigBean("gateway", Map.of("credentials", credentials))),
                        null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups().get(0).properties())
                .extracting(ConfigProperty::key, ConfigProperty::value)
                .containsExactly(tuple("credentials", "******"));
    }

    /**
     * {@code server.servlet.session.cookie.*} is configuration, not a secret: the whole-key
     * cookie rule must not collapse it, however deep the tree nests it.
     */
    @Test
    void map_shouldKeepSessionCookieConfigurationVisible() {
        Map<String, Object> cookie = new LinkedHashMap<>();
        cookie.put("name", "JSESSIONID");
        cookie.put("sameSite", "Lax");
        Map<String, Object> servlet = Map.of("session", Map.of("cookie", cookie));

        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of(
                                "server-ServerProperties",
                                new ConfigPropsResponse.ConfigBean("server", Map.of("servlet", servlet))),
                        null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups().get(0).properties())
                .extracting(ConfigProperty::key, ConfigProperty::value)
                .containsExactly(
                        tuple("servlet.session.cookie.name", "JSESSIONID"),
                        tuple("servlet.session.cookie.sameSite", "Lax"));
    }

    /** List elements are indexed the way Spring's own property syntax writes them. */
    @Test
    void map_shouldIndexListElementsLikeSpringPropertyKeys() {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("host", "a.example.org");
        List<Object> servers = List.of(server, "plain");

        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of("pool", new ConfigPropsResponse.ConfigBean("pool", Map.of("servers", servers))), null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups().get(0).properties())
                .extracting(ConfigProperty::key, ConfigProperty::value)
                .containsExactly(tuple("servers[0].host", "a.example.org"), tuple("servers[1]", "plain"));
    }

    /** An empty container still shows up as the property it is, rather than vanishing. */
    @Test
    void map_shouldKeepAnEmptyContainerAsASingleProperty() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of("pool", new ConfigPropsResponse.ConfigBean("pool", Map.of("servers", List.of()))),
                        null)));

        ConfigInfo result = mapper.map(configprops, false);

        assertThat(result.groups().get(0).properties())
                .extracting(ConfigProperty::key, ConfigProperty::value)
                .containsExactly(tuple("servers", "[]"));
    }

    @Test
    void map_shouldReturnRealValueWhenUnmaskIsTrue() {
        ConfigPropsResponse configprops = new ConfigPropsResponse(Map.of(
                "application",
                new ConfigPropsResponse.ConfigContext(
                        Map.of(
                                "datasource",
                                new ConfigPropsResponse.ConfigBean(
                                        "spring.datasource", Map.of("password", "secret123"))),
                        null)));

        ConfigInfo result = mapper.map(configprops, true);
        assertThat(result.groups().get(0).properties().get(0).value()).isEqualTo("secret123");
    }
}
