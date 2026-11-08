package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.web.InsightsController;
import org.peekaboot.backend.lifecycle.ApplicationStoppedListener;
import org.peekaboot.backend.lifecycle.web.LifecycleController;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * Component scanning names a host's own bean after its class. A Peekaboot bean under that name
 * stops the context with BeanDefinitionOverrideException, so every one takes the peekaboot
 * prefix.
 */
class PeekabootBeanNamesTest {

    private static final String PREFIX = "peekaboot";

    /** BeanNameViewResolver renders the view name {@code error} with the bean of that name, and Boot's whitelabel view backs off for it. */
    private static final List<String> NAMES_SPRING_DICTATES = List.of("error");

    private final WebApplicationContextRunner contextRunner =
            RegisteredAutoConfigurations.servletRunner().withPropertyValues("peekaboot.enabled=true");

    @Test
    void startsBesideHostControllersNamedInsightsAndLifecycleController() {
        try (LogCapture insightsStartup = LogCapture.attach(InsightsService.class);
                LogCapture stopBanner = LogCapture.attach(ApplicationStoppedListener.class)) {
            contextRunner.withUserConfiguration(HostControllersConfig.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean("insightsController")).isInstanceOf(HostInsightsController.class);
                assertThat(context.getBean("lifecycleController")).isInstanceOf(HostLifecycleController.class);
                assertThat(context).hasSingleBean(InsightsController.class);
                assertThat(context).hasSingleBean(LifecycleController.class);
            });
        }
    }

    /** A controller added later is held to the same rule. */
    @Test
    void everyPeekabootControllerTakesThePeekabootPrefix() {
        try (LogCapture insightsStartup = LogCapture.attach(InsightsService.class);
                LogCapture stopBanner = LogCapture.attach(ApplicationStoppedListener.class)) {
            contextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansWithAnnotation(RestController.class).entrySet().stream()
                                .filter(bean ->
                                        bean.getValue().getClass().getName().startsWith("org.peekaboot."))
                                .map(Map.Entry::getKey))
                        .contains(
                                "peekabootInsightsController",
                                "peekabootLifecycleController",
                                "peekabootTraceController",
                                "peekabootApiController")
                        .allSatisfy(name -> assertThat(name).startsWith(PREFIX));
            });
        }
    }

    /** Read off the declarations, so a bean the test context's conditions leave out is held to the rule too. */
    @Test
    void everyDeclaredBeanTakesThePeekabootPrefix() {
        assertThat(declaredBeans().stream().map(DeclaredBean::name).filter(name -> !name.startsWith(PREFIX)))
                .containsExactlyInAnyOrderElementsOf(NAMES_SPRING_DICTATES);
    }

    /** A condition that matches by name guards the name its own bean is registered under. */
    @Test
    void everyNameMatchedConditionNamesItsOwnBean() {
        assertThat(declaredBeans())
                .filteredOn(bean -> !bean.missingBeanNames().isEmpty())
                .isNotEmpty()
                .allSatisfy(bean -> assertThat(bean.missingBeanNames()).containsExactly(bean.name()));
    }

    /** A host holds every name a Peekaboot bean would carry without the prefix. */
    @Test
    void startsBesideHostBeansUnderEveryUnprefixedName() {
        WebApplicationContextRunner runner = contextRunner;
        for (String name : unprefixedNames()) {
            runner = runner.withBean(name, HostBean.class, HostBean::new);
        }
        try (LogCapture insightsStartup = LogCapture.attach(InsightsService.class);
                LogCapture stopBanner = LogCapture.attach(ApplicationStoppedListener.class)) {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(TraceStore.class);
                assertThat(context.getBeansOfType(HostBean.class)).containsOnlyKeys(unprefixedNames());
            });
        }
    }

    /**
     * Boot registers a filter under its registration's name, and the servlet context refuses a
     * second filter of a name it already holds.
     */
    @Test
    void everyPeekabootFilterRegistersUnderItsBeanName() {
        try (LogCapture insightsStartup = LogCapture.attach(InsightsService.class);
                LogCapture stopBanner = LogCapture.attach(ApplicationStoppedListener.class)) {
            contextRunner
                    .withPropertyValues(
                            "peekaboot.dev-toolbar.enabled=true",
                            "peekaboot.security.enabled=true",
                            "peekaboot.security.password=secret")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBeansOfType(FilterRegistrationBean.class)
                                        .entrySet())
                                .filteredOn(registration -> registration
                                        .getValue()
                                        .getFilter()
                                        .getClass()
                                        .getName()
                                        .startsWith("org.peekaboot."))
                                .allSatisfy(registration -> assertThat(
                                                registration.getValue().getFilterName())
                                        .isEqualTo(registration.getKey()))
                                .extracting(Map.Entry::getKey)
                                .containsExactlyInAnyOrder(
                                        "peekabootDevToolbarFilter",
                                        "peekabootRequestCaptureFilter",
                                        "peekabootDashboardAuthenticationFilter");
                    });
        }
    }

    private record DeclaredBean(String name, List<String> missingBeanNames) {}

    /**
     * Every {@code @Bean} method of every registered auto-configuration, its nested classes and what it imports.
     *
     * <p>The walk reads the methods each class declares, its nested classes and its direct {@code @Import}s; it
     * misses inherited methods, imports through a meta-annotation and {@code ImportSelector}s.
     */
    private static List<DeclaredBean> declaredBeans() {
        return RegisteredAutoConfigurations.classes().stream()
                .flatMap(PeekabootBeanNamesTest::withNestedAndImported)
                .distinct()
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, Bean.class))
                .map(PeekabootBeanNamesTest::declaredBean)
                .toList();
    }

    private static Stream<Class<?>> withNestedAndImported(Class<?> type) {
        Import imported = type.getAnnotation(Import.class);
        Stream<Class<?>> children = Stream.concat(
                Arrays.stream(type.getDeclaredClasses()),
                imported != null ? Arrays.stream(imported.value()) : Stream.empty());
        return Stream.concat(Stream.of(type), children.flatMap(PeekabootBeanNamesTest::withNestedAndImported));
    }

    private static DeclaredBean declaredBean(Method method) {
        Bean bean = AnnotatedElementUtils.findMergedAnnotation(method, Bean.class);
        ConditionalOnMissingBean condition =
                AnnotatedElementUtils.findMergedAnnotation(method, ConditionalOnMissingBean.class);
        return new DeclaredBean(
                bean != null && bean.name().length > 0 ? bean.name()[0] : method.getName(),
                condition != null ? List.of(condition.name()) : List.of());
    }

    /** What a host's bean would be called if Peekaboot's carried no prefix. */
    private static List<String> unprefixedNames() {
        return declaredBeans().stream()
                .map(DeclaredBean::name)
                .filter(name -> !NAMES_SPRING_DICTATES.contains(name))
                .map(name -> name.startsWith(PREFIX) ? StringUtils.uncapitalize(name.substring(PREFIX.length())) : name)
                .distinct()
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class HostControllersConfig {

        @Bean
        HostInsightsController insightsController() {
            return new HostInsightsController();
        }

        @Bean
        HostLifecycleController lifecycleController() {
            return new HostLifecycleController();
        }
    }

    static class HostInsightsController {}

    static class HostLifecycleController {}

    static class HostBean {}
}
