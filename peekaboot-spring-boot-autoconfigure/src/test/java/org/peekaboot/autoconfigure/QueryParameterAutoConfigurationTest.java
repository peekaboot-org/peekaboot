package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import net.ttddyy.observation.tracing.QueryContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class QueryParameterAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(QueryParameterAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void registersTheFilterWithDatasourceMicrometerPresent() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(QueryParameterObservationFilter.class);
        });
    }

    /** A host that excludes the starter's instrumentation has no QueryContext to read. */
    @Test
    void staysAwayWithoutDatasourceMicrometer() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(QueryContext.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(QueryParameterObservationFilter.class);
                });
    }

    @Test
    void staysAwayWhenPeekabootIsDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QueryParameterObservationFilter.class));
    }

    /** Without tracing there is no store for the tag to reach, and no reason to put values on a span. */
    @Test
    void staysAwayWhenTracingIsDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.tracing.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(QueryParameterObservationFilter.class));
    }

    /**
     * The trace store the tag would land in is servlet-only; a non-web application (a worker, a
     * batch job) has nowhere for the tag to be read, so it must not put raw bind values on its spans.
     */
    @Test
    void staysAwayOnNonServletApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(QueryParameterAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(QueryParameterObservationFilter.class);
                });
    }

    @Test
    void staysAwayOnReactiveApplication() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(QueryParameterAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(QueryParameterObservationFilter.class);
                });
    }
}
