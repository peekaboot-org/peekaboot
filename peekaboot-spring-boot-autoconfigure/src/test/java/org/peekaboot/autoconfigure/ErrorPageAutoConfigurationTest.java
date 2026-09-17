package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.errorpage.PeekabootErrorExceptionResolver;
import org.peekaboot.backend.errorpage.PeekabootErrorView;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.BeanNameViewResolver;

/**
 * Peekaboot's page stands exactly where Boot's whitelabel page would: it takes the bean
 * name Boot's own view uses, under Boot's own conditions, so every error page an
 * application brings - a status template, an error template, its own view or controller -
 * wins without Peekaboot knowing about it. {@code peekaboot.error-page.override} is the
 * opt-out of that back-off: with it set, Peekaboot's page wins instead.
 */
class ErrorPageAutoConfigurationTest {

    // DispatcherServletAutoConfiguration for the DispatcherServletPath bean
    // ErrorMvcAutoConfiguration.ErrorPageCustomizer needs
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ErrorPageAutoConfiguration.class,
                    ErrorMvcAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void replacesTheWhitelabelPageWhenTheSwitchIsOn() {
        contextRunner.withPropertyValues("peekaboot.error-page.enabled=true").run(context -> {
            assertThat(context).hasBean("error");
            assertThat(context.getBean("error")).isInstanceOf(PeekabootErrorView.class);
            // proves the page is actually reachable: BeanNameViewResolver is what resolves "error" to this bean
            assertThat(context).hasSingleBean(BeanNameViewResolver.class);
            assertThat(context).doesNotHaveBean(ErrorPageAutoConfiguration.PeekabootErrorViewResolver.class);
        });
    }

    /**
     * Neither optional type is guaranteed on the classpath - Jersey is a servlet app with no
     * {@code DispatcherServlet} - and without a class-level guard, {@code @ConditionalOnWebApplication}
     * alone would still match, reaching the bean method and failing the host application's startup
     * outright. An {@code ErrorAttributes} bean is supplied here so that, absent the guard, the bean
     * would otherwise be created successfully - proving the back-off is the guard's doing.
     */
    @Test
    void backsOffWithoutDispatcherServletOnTheClasspath() {
        new WebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(DispatcherServlet.class))
                .withConfiguration(AutoConfigurations.of(ErrorPageAutoConfiguration.class))
                .withBean(ErrorAttributes.class, () -> mock(ErrorAttributes.class))
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.error-page.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootErrorView.class);
                });
    }

    /**
     * ErrorAttributes lives in the same optional dependency; absent, there is nothing to render
     * the page from. A stub bean of the filtered type isn't an option here - Mockito can't
     * subclass a type the context's own classloader has just been told to hide - so this is the
     * case a missing guard would leave with no {@code ErrorAttributes} bean of any kind, and the
     * bean method failing to resolve its parameter rather than backing off.
     */
    @Test
    void backsOffWithoutErrorAttributesOnTheClasspath() {
        new WebApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader(ErrorAttributes.class))
                .withConfiguration(AutoConfigurations.of(ErrorPageAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true", "peekaboot.error-page.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PeekabootErrorView.class);
                });
    }

    @Test
    void leavesBootsWhitelabelPageAloneWithTheSwitchOff() {
        contextRunner.withPropertyValues("peekaboot.error-page.enabled=false").run(context -> {
            assertThat(context).hasBean("error");
            assertThat(context.getBean("error")).isNotInstanceOf(PeekabootErrorView.class);
        });
    }

    @Test
    void backsOffWithoutTheMasterSwitch() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ErrorPageAutoConfiguration.class))
                .withPropertyValues("peekaboot.error-page.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootErrorView.class));
    }

    /** An application that turned the whitelabel page off did so to keep its own; Peekaboot respects that. */
    @Test
    void backsOffWhereTheApplicationDisabledTheWhitelabelPage() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true", "spring.web.error.whitelabel.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootErrorView.class));
    }

    /** A templates/error.html is the application's own page; Boot skips its whitelabel view for it and so does Peekaboot. */
    @Test
    void backsOffWhereTheApplicationHasAnErrorTemplate() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true", "test.error-template=true")
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootErrorView.class));
    }

    @Test
    void backsOffWhereTheApplicationDeclaresItsOwnErrorView() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true")
                .withUserConfiguration(OwnErrorView.class)
                .run(context -> assertThat(context.getBean("error")).isSameAs(OwnErrorView.VIEW));
    }

    /**
     * With the override on, Peekaboot resolves the page itself instead of filling Boot's
     * fallback slot, so none of the back-off conditions apply and no bean named {@code error}
     * is registered - an application {@code error} bean would clash on the name.
     */
    @Test
    void takesPrecedenceOverTheApplicationsOwnErrorViewWithTheOverrideOn() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true", "peekaboot.error-page.override=true")
                .withUserConfiguration(OwnErrorView.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorViewResolver.class);
                    assertThat(context).hasSingleBean(ErrorPageAutoConfiguration.PeekabootErrorViewResolver.class);
                    assertThat(context.getBean("error")).isSameAs(OwnErrorView.VIEW);
                });
    }

    /** The template condition gates the fallback path only; the override path registers regardless. */
    @Test
    void registersTheResolverWithAnErrorTemplatePresent() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.error-page.enabled=true",
                        "peekaboot.error-page.override=true",
                        "test.error-template=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorViewResolver.class);
                    assertThat(context).hasSingleBean(ErrorPageAutoConfiguration.PeekabootErrorViewResolver.class);
                });
    }

    /**
     * The override path resolves the page itself and never asks BeanNameViewResolver for a bean
     * named {@code error}, so the whitelabel switch that removes that resolver is irrelevant to it.
     */
    @Test
    void registersTheResolverWithTheWhitelabelPageDisabled() {
        contextRunner
                .withPropertyValues(
                        "peekaboot.error-page.enabled=true",
                        "peekaboot.error-page.override=true",
                        "spring.web.error.whitelabel.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorViewResolver.class);
                    assertThat(context).hasSingleBean(ErrorPageAutoConfiguration.PeekabootErrorViewResolver.class);
                });
    }

    /** The override is an opt-in on top of the page, never a way to turn the page on. */
    @Test
    void registersNothingWhereTheOverrideIsOnButThePageIsOff() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=false", "peekaboot.error-page.override=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ErrorPageAutoConfiguration.PeekabootErrorViewResolver.class);
                    assertThat(context.getBean("error")).isNotInstanceOf(PeekabootErrorView.class);
                });
    }

    /**
     * The two paths must be mutually exclusive; with nothing else suppressing the fallback
     * condition, only the override condition itself can keep it from registering alongside the
     * resolver.
     */
    @Test
    void registersOnlyTheResolverWithTheOverrideOn() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true", "peekaboot.error-page.override=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorPageAutoConfiguration.PeekabootErrorViewResolver.class);
                    assertThat(context.getBean("error")).isNotInstanceOf(PeekabootErrorView.class);
                });
    }

    /** Pins that an explicit false behaves the same as leaving the override unset. */
    @Test
    void registersOnlyTheFallbackPathWithTheOverrideExplicitlyOff() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true", "peekaboot.error-page.override=false")
                .run(context -> {
                    assertThat(context.getBean("error")).isInstanceOf(PeekabootErrorView.class);
                    assertThat(context).doesNotHaveBean(ErrorPageAutoConfiguration.PeekabootErrorViewResolver.class);
                });
    }

    /**
     * The MVC path registers alongside the ErrorViewResolver path, under the same condition:
     * it is what lets Peekaboot's page beat an application's own {@code @ControllerAdvice}.
     */
    @Test
    void registersTheExceptionResolverWithTheOverrideOn() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true", "peekaboot.error-page.override=true")
                .run(context -> assertThat(context).hasSingleBean(PeekabootErrorExceptionResolver.class));
    }

    @Test
    void registersNoExceptionResolverWithTheOverrideOff() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true", "peekaboot.error-page.override=false")
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootErrorExceptionResolver.class));
    }

    @Test
    void registersNoExceptionResolverWithTheOverrideUnset() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootErrorExceptionResolver.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnErrorView {

        static final View VIEW =
                (model, request, response) -> response.getWriter().write("the application's own page");

        @Bean(name = "error")
        View errorView() {
            return VIEW;
        }
    }
}
