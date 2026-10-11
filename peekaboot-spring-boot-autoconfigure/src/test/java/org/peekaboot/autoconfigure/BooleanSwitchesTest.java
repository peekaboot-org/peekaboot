package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzedException;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StringUtils;

/**
 * {@code @ConditionalOnBooleanProperty} compares the raw value with {@code equalsIgnoreCase},
 * while the binder behind {@code PeekabootProperties} trims it and also reads {@code yes},
 * {@code on} and {@code 1} as true - so such a value left a feature off while the Config tab
 * showed it on. Startup refuses only the values the two read differently.
 */
class BooleanSwitchesTest {

    /** The binder reads each of these as true; the conditions match none of them. */
    private static final List<String> READ_AS_TRUE_ONLY_BY_THE_BINDER =
            List.of("yes", "YES", "on", "On", "1", " true", "true ", "\tTRUE");

    /** The binder cannot convert these, and the detection reads a blank one as unset. */
    private static final List<String> UNREADABLE_BY_THE_BINDER = List.of("", "  ", "enabled");

    /** Both read these as false, except where a condition also matches only {@code false}. */
    private static final List<String> READ_AS_FALSE_LENIENTLY = List.of("no", "OFF", "0", " false", "false ");

    static Stream<Arguments> everySwitchWithEveryRefusedValue() {
        return PeekabootPropertyKeys.BOOLEAN_SWITCHES.stream()
                .flatMap(key -> Stream.concat(
                                READ_AS_TRUE_ONLY_BY_THE_BINDER.stream(), UNREADABLE_BY_THE_BINDER.stream())
                        .map(value -> Arguments.of(key, value)));
    }

    @ParameterizedTest(name = "{0}=''{1}''")
    @MethodSource("everySwitchWithEveryRefusedValue")
    void refusesAValueTheBinderReadsDifferentlyFromTheConditions(String key, String value) {
        MockEnvironment environment = new MockEnvironment().withProperty(key, value);

        assertThatThrownBy(() -> BooleanSwitches.requireUnambiguous(environment))
                .isInstanceOfSatisfying(FailureAnalyzedException.class, failure -> {
                    FailureAnalysis analysis = failure.analysis();
                    assertThat(analysis.getDescription()).contains(key + " = '" + value + "'");
                    assertThat(analysis.getAction()).contains(key);
                });
    }

    /** Spring compares the switch value with {@code equalsIgnoreCase}, so any casing is a real value. */
    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "TRUE", "False"})
    void acceptsTrueAndFalseInAnyCase(String value) {
        MockEnvironment environment = new MockEnvironment();
        PeekabootPropertyKeys.BOOLEAN_SWITCHES.forEach(key -> environment.setProperty(key, value));

        assertThatCode(() -> BooleanSwitches.requireUnambiguous(environment)).doesNotThrowAnyException();
    }

    /**
     * A {@code true}-only condition and the binder both take these as off, so they behave as
     * {@code false} did on every released version.
     */
    static Stream<Arguments> everySwitchGatedOnlyOnTrueWithEveryLenientFalse() {
        return PeekabootPropertyKeys.BOOLEAN_SWITCHES.stream()
                .filter(key -> !PeekabootPropertyKeys.SWITCHES_ALSO_GATED_ON_FALSE.contains(key))
                .flatMap(key -> READ_AS_FALSE_LENIENTLY.stream().map(value -> Arguments.of(key, value)));
    }

    @ParameterizedTest(name = "{0}=''{1}''")
    @MethodSource("everySwitchGatedOnlyOnTrueWithEveryLenientFalse")
    void acceptsALenientFalseWhereOnlyTrueIsMatched(String key, String value) {
        MockEnvironment environment = new MockEnvironment().withProperty(key, value);

        assertThatCode(() -> BooleanSwitches.requireUnambiguous(environment)).doesNotThrowAnyException();
    }

    /**
     * A {@code havingValue = false} condition matches neither {@code true} nor {@code no}, so
     * {@code no} drops both of the beans it chooses between while the binder reads plain false.
     */
    static Stream<Arguments> everySwitchAlsoGatedOnFalseWithEveryLenientFalse() {
        return PeekabootPropertyKeys.SWITCHES_ALSO_GATED_ON_FALSE.stream()
                .flatMap(key -> READ_AS_FALSE_LENIENTLY.stream().map(value -> Arguments.of(key, value)));
    }

    @ParameterizedTest(name = "{0}=''{1}''")
    @MethodSource("everySwitchAlsoGatedOnFalseWithEveryLenientFalse")
    void refusesALenientFalseWhereFalseIsMatchedToo(String key, String value) {
        MockEnvironment environment = new MockEnvironment().withProperty(key, value);

        assertThatThrownBy(() -> BooleanSwitches.requireUnambiguous(environment))
                .isInstanceOfSatisfying(
                        FailureAnalyzedException.class,
                        failure ->
                                assertThat(failure.analysis().getDescription()).contains(key + " = '" + value + "'"));
    }

    @Test
    void acceptsSwitchesLeftUnset() {
        assertThatCode(() -> BooleanSwitches.requireUnambiguous(new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    /** One report naming every offending switch, so fixing one does not just reveal the next. */
    @Test
    void namesEveryOffendingSwitchAtOnce() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(PeekabootPropertyKeys.ERROR_PAGE_OVERRIDE, "yes")
                .withProperty(PeekabootPropertyKeys.SECURITY_ENABLED, "on")
                .withProperty(PeekabootPropertyKeys.TRACING_ENABLED, "true")
                .withProperty(PeekabootPropertyKeys.INSIGHTS_ENABLED, "no");

        assertThatThrownBy(() -> BooleanSwitches.requireUnambiguous(environment))
                .isInstanceOfSatisfying(
                        FailureAnalyzedException.class,
                        failure -> assertThat(failure.analysis().getDescription())
                                .contains("peekaboot.error-page.override = 'yes'")
                                .contains("peekaboot.security.enabled = 'on'")
                                .doesNotContain(PeekabootPropertyKeys.TRACING_ENABLED)
                                .doesNotContain(PeekabootPropertyKeys.INSIGHTS_ENABLED));
    }

    /**
     * The list is the only place that decides which switches are checked, so a new
     * {@code @ConditionalOnBooleanProperty} on a {@code peekaboot.*} key must land in it.
     */
    @Test
    void coversEverySwitchAnAutoConfigurationIsGatedOn() throws ClassNotFoundException {
        Set<String> gatedSwitches = gatedSwitches(condition -> true);

        assertThat(gatedSwitches).isNotEmpty();
        assertThat(PeekabootPropertyKeys.BOOLEAN_SWITCHES).containsExactlyInAnyOrderElementsOf(gatedSwitches);
    }

    /** Only these switches refuse {@code no}, so a new {@code havingValue = false} must land here. */
    @Test
    void coversEverySwitchAnAutoConfigurationIsAlsoGatedOnFalse() throws ClassNotFoundException {
        Set<String> gatedOnFalse = gatedSwitches(condition -> !condition.havingValue());

        assertThat(gatedOnFalse).isNotEmpty();
        assertThat(PeekabootPropertyKeys.SWITCHES_ALSO_GATED_ON_FALSE)
                .containsExactlyInAnyOrderElementsOf(gatedOnFalse);
    }

    private Set<String> gatedSwitches(Predicate<ConditionalOnBooleanProperty> which) throws ClassNotFoundException {
        Set<String> gatedSwitches = new HashSet<>();
        for (String className :
                ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())) {
            if (className.startsWith("org.peekaboot.")) {
                collectGatedSwitches(Class.forName(className), which, gatedSwitches);
            }
        }
        return gatedSwitches;
    }

    private static void collectGatedSwitches(
            Class<?> type, Predicate<ConditionalOnBooleanProperty> which, Set<String> gatedSwitches) {
        collectGatedSwitches((AnnotatedElement) type, which, gatedSwitches);
        Arrays.stream(type.getDeclaredMethods()).forEach(method -> collectGatedSwitches(method, which, gatedSwitches));
        Arrays.stream(type.getDeclaredClasses()).forEach(nested -> collectGatedSwitches(nested, which, gatedSwitches));
    }

    private static void collectGatedSwitches(
            AnnotatedElement element, Predicate<ConditionalOnBooleanProperty> which, Set<String> gatedSwitches) {
        MergedAnnotations.from(element).stream(ConditionalOnBooleanProperty.class)
                .map(MergedAnnotation::synthesize)
                .filter(which)
                .flatMap(BooleanSwitchesTest::keys)
                .filter(key -> key.startsWith("peekaboot."))
                .forEach(gatedSwitches::add);
    }

    private static Stream<String> keys(ConditionalOnBooleanProperty condition) {
        String[] names = condition.name().length > 0 ? condition.name() : condition.value();
        String prefix = StringUtils.hasText(condition.prefix()) ? condition.prefix() + "." : "";
        return Arrays.stream(names).map(name -> prefix + name);
    }
}
