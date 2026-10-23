package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The composed conditions match in exactly the launches the condition stacks they replace
 * match in: every web application type, with each switch unset, true and false.
 */
class ComposedConditionsTest {

    private static final List<String> SWITCHES = List.of(
            PeekabootPropertyKeys.ENABLED, PeekabootPropertyKeys.TRACING_ENABLED, PeekabootPropertyKeys.TRACING_ASYNC);

    private static final List<String> SWITCH_VALUES = List.of("unset", "true", "false");

    static Stream<Arguments> composedAndCopiedStacks() {
        return Stream.of(
                Arguments.of(ComposedServlet.class, CopiedServlet.class),
                Arguments.of(ComposedTracing.class, CopiedTracing.class),
                Arguments.of(ComposedTracingWithASwitch.class, CopiedTracingWithASwitch.class));
    }

    @ParameterizedTest
    @MethodSource("composedAndCopiedStacks")
    void matchesInExactlyTheLaunchesTheCopiedStackMatchesIn(Class<?> composed, Class<?> copied) {
        Set<String> composedMatches = new TreeSet<>();
        Set<String> copiedMatches = new TreeSet<>();
        List<String> launches = new ArrayList<>();
        forEveryLaunch((launch, runner) -> {
            launches.add(launch);
            runner.withUserConfiguration(composed, copied).run(context -> {
                if (context.getBeanNamesForType(composed).length > 0) {
                    composedMatches.add(launch);
                }
                if (context.getBeanNamesForType(copied).length > 0) {
                    copiedMatches.add(launch);
                }
            });
        });

        assertThat(copiedMatches)
                .as("the copied stack must match some launches and not others, or the comparison proves nothing")
                .isNotEmpty()
                .hasSizeLessThan(launches.size());
        assertThat(composedMatches).isEqualTo(copiedMatches);
    }

    private interface LaunchConsumer {
        void accept(String launch, AbstractApplicationContextRunner<?, ?, ?> runner);
    }

    private static void forEveryLaunch(LaunchConsumer consumer) {
        List<AbstractApplicationContextRunner<?, ?, ?>> runners = List.of(
                new WebApplicationContextRunner(),
                new ReactiveWebApplicationContextRunner(),
                new ApplicationContextRunner());
        List<String> runnerNames = List.of("servlet", "reactive", "none");
        for (int r = 0; r < runners.size(); r++) {
            for (List<String> values : everyCombination(SWITCHES.size())) {
                List<String> properties = new ArrayList<>();
                StringBuilder launch = new StringBuilder(runnerNames.get(r));
                for (int i = 0; i < SWITCHES.size(); i++) {
                    launch.append(' ').append(SWITCHES.get(i)).append('=').append(values.get(i));
                    if (!"unset".equals(values.get(i))) {
                        properties.add(SWITCHES.get(i) + "=" + values.get(i));
                    }
                }
                consumer.accept(launch.toString(), withProperties(runners.get(r), properties));
            }
        }
    }

    private static AbstractApplicationContextRunner<?, ?, ?> withProperties(
            AbstractApplicationContextRunner<?, ?, ?> runner, List<String> properties) {
        return runner.withPropertyValues(properties.toArray(String[]::new));
    }

    private static List<List<String>> everyCombination(int length) {
        if (length == 0) {
            return List.of(List.of());
        }
        List<List<String>> combinations = new ArrayList<>();
        for (String value : SWITCH_VALUES) {
            for (List<String> rest : everyCombination(length - 1)) {
                List<String> combination = new ArrayList<>();
                combination.add(value);
                combination.addAll(rest);
                combinations.add(combination);
            }
        }
        return combinations;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnPeekabootServlet
    static class ComposedServlet {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
    static class CopiedServlet {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnPeekabootTracing
    static class ComposedTracing {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
    @ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ENABLED, matchIfMissing = true)
    static class CopiedTracing {}

    /** The shape of the async and event-listener auto-configurations: one more switch of their own. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnPeekabootTracing
    @ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ASYNC, matchIfMissing = true)
    static class ComposedTracingWithASwitch {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
    @ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ENABLED, matchIfMissing = true)
    @ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ASYNC, matchIfMissing = true)
    static class CopiedTracingWithASwitch {}
}
