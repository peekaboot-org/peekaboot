package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.devtoolbar.DevToolbarFilter;
import org.peekaboot.backend.devtoolbar.ToolbarDataProvider;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DevToolbarAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DevToolbarAutoConfiguration.class,
                    PeekabootAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void createsBeansWhenDevToolbarEnabled() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true")
                .withUserConfiguration(TracerOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolbarDataProvider.class);
                    assertThat(context).hasBean("devToolbarFilter");
                });
    }

    /** Every toolbar bean is {@code @ConditionalOnMissingBean}, so an application can supply its own. */
    @Test
    void userSuppliedSameNamedBeansReplaceTheDefaults() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true")
                .withUserConfiguration(TracerOnlyConfig.class, UserSameNamedBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("toolbarDataProvider"))
                            .isSameAs(UserSameNamedBeansConfig.TOOLBAR_DATA_PROVIDER);
                    assertThat(context.getBean("devToolbarFilter"))
                            .isSameAs(UserSameNamedBeansConfig.FILTER_REGISTRATION);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSameNamedBeansConfig {

        static final ToolbarDataProvider TOOLBAR_DATA_PROVIDER =
                new ToolbarDataProvider(new PeekabootTracingProperties());
        static final FilterRegistrationBean<DevToolbarFilter> FILTER_REGISTRATION = new FilterRegistrationBean<>();

        @Bean
        ToolbarDataProvider toolbarDataProvider() {
            return TOOLBAR_DATA_PROVIDER;
        }

        @Bean
        FilterRegistrationBean<DevToolbarFilter> devToolbarFilter() {
            return FILTER_REGISTRATION;
        }
    }

    /**
     * The springdoc property decides where idle mode applies; it has to reach the filter. springdoc
     * serves a slash-less value at the same place, since Spring's pattern parser adds the slash.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/admin/docs.html", "admin/docs.html"})
    void theCustomisedSwaggerUiPathReachesTheToolbarFilter(String swaggerUiPath) {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true", "springdoc.swagger-ui.path=" + swaggerUiPath)
                .withUserConfiguration(TracerOnlyConfig.class)
                .run(context -> {
                    DevToolbarFilter filter =
                            (DevToolbarFilter) context.getBean("devToolbarFilter", FilterRegistrationBean.class)
                                    .getFilter();
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/swagger-ui/index.html");
                    request.setServletPath("/admin/swagger-ui/index.html");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> {
                        res.setContentType("text/html");
                        res.getWriter().write("<html><body></body></html>");
                    });

                    assertThat(response.getContentAsString()).contains("\"idle\":true");
                });
    }

    /** Swagger UI fetches its spec from springdoc.api-docs.path; idle mode skips that call wherever it is configured. */
    @Test
    void theCustomisedApiDocsPathReachesTheToolbarFilter() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true", "springdoc.api-docs.path=/spec")
                .withUserConfiguration(TracerOnlyConfig.class)
                .run(context -> {
                    DevToolbarFilter filter =
                            (DevToolbarFilter) context.getBean("devToolbarFilter", FilterRegistrationBean.class)
                                    .getFilter();
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
                    request.setServletPath("/swagger-ui/index.html");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> {
                        res.setContentType("text/html");
                        res.getWriter().write("<html><body></body></html>");
                    });

                    assertThat(response.getContentAsString())
                            .contains("\"ignoredPathPrefixes\":[\"/swagger-ui/\",\"/spec\"]");
                });
    }

    /** The error page is rendered on the ERROR dispatch; a REQUEST-only registration never sees it. */
    @Test
    void theToolbarFilterIsRegisteredForTheErrorDispatchToo() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true")
                .withUserConfiguration(TracerOnlyConfig.class)
                .run(context -> assertThat(context.getBean("devToolbarFilter", FilterRegistrationBean.class))
                        .extracting(FilterRegistrationBean::determineDispatcherTypes)
                        .isEqualTo(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR)));
    }

    @Test
    void doesNotCreateBeansWhenDevToolbarDisabled() {
        contextRunner.withPropertyValues("peekaboot.dev-toolbar.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
            assertThat(context).doesNotHaveBean("devToolbarFilter");
        });
    }

    @Test
    void doesNotCreateBeansWhenDevToolbarPropertyMissing() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
            assertThat(context).doesNotHaveBean("devToolbarFilter");
        });
    }

    @Test
    void doesNotCreateTheFilterWhenTracerBeanMissing() {
        // devToolbarFilter's @ConditionalOnBean(Tracer.class) must keep it unregistered, while
        // toolbarDataProvider (no such condition) still registers.
        contextRunner.withPropertyValues("peekaboot.dev-toolbar.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ToolbarDataProvider.class);
            assertThat(context).doesNotHaveBean("devToolbarFilter");
        });
    }

    /**
     * Micrometer Tracing's Brave bridge gets its {@link Tracer} from Boot's
     * {@code BraveAutoConfiguration}, which sorts after every {@code org.peekaboot} class by
     * name. Without an ordering edge the {@code @ConditionalOnBean(Tracer.class)} checks run
     * before that bean is defined, and the toolbar is silently never injected. The user
     * configurations of the other tests here register their Tracer ahead of every
     * auto-configuration, so only an auto-configuration of that name can prove the edge.
     */
    @Test
    void theToolbarSeesATracerFromBravesAutoConfiguration() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(BraveAutoConfiguration.class))
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("devToolbarFilter");
                });
    }

    @Test
    void peekabootDisabledWinsOverDevToolbarFlag() {
        // peekaboot.enabled=false skips PeekabootAutoConfiguration (and with it
        // the PeekabootProperties bean); the toolbar must switch off cleanly
        // instead of failing the context on the missing bean.
        contextRunner
                .withPropertyValues("peekaboot.enabled=false", "peekaboot.dev-toolbar.enabled=true")
                .withUserConfiguration(TracerOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ToolbarDataProvider.class);
                    assertThat(context).doesNotHaveBean("devToolbarFilter");
                });
    }

    @Test
    void registersTheTomcatForwardCustomizerWhenTomcatIsPresent() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true")
                .withUserConfiguration(TracerOnlyConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TomcatForwardResponseCustomizer.class);
                });
    }

    @Test
    void doesNotRegisterTheTomcatForwardCustomizerWithoutTomcat() {
        contextRunner
                .withPropertyValues("peekaboot.dev-toolbar.enabled=true")
                .withUserConfiguration(TracerOnlyConfig.class)
                .withClassLoader(new FilteredClassLoader(org.apache.catalina.Context.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TomcatForwardResponseCustomizer.class);
                });
    }

    @Configuration
    static class TracerOnlyConfig {
        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }
}
