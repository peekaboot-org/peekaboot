package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.errorpage.PeekabootErrorView;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;

/**
 * Which page a failing request actually gets. The conditions in
 * {@link ErrorPageAutoConfigurationTest} decide which beans exist; this decides which of them
 * Boot reaches, by asking {@code BasicErrorController} the same question the ERROR dispatch does.
 */
class ErrorPageResolutionOrderTest {

    private static final String STATIC_PAGE = "spring.web.resources.static-locations=classpath:/error-page-static/";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ErrorPageAutoConfiguration.class,
                    ErrorMvcAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true", "peekaboot.error-page.enabled=true");

    /**
     * Today's behaviour: a static error/500.html is the application's page and Boot resolves it
     * first. Rendering the resolved view, not just typing it, proves the body is the
     * application's page and not merely "something that isn't Peekaboot's".
     */
    @Test
    void theApplicationsStaticPageWinsWithTheOverrideOff() {
        contextRunner.withPropertyValues(STATIC_PAGE).run(context -> {
            MockHttpServletRequest request = errorRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            ModelAndView resolved = context.getBean(BasicErrorController.class).errorHtml(request, response);
            assertThat(resolved).isNotNull();
            assertThat(resolved.getView()).isNotInstanceOf(PeekabootErrorView.class);
            resolved.getView().render(resolved.getModel(), request, response);
            assertThat(response.getContentAsString()).contains("the application's own static page");
        });
    }

    /** The override's resolver replaces DefaultErrorViewResolver, which is what would otherwise serve the static page. */
    @Test
    void peekabootWinsOverTheStaticPageWithTheOverrideOn() {
        contextRunner
                .withPropertyValues(STATIC_PAGE, "peekaboot.error-page.override=true")
                .run(context -> {
                    ModelAndView resolved = resolve(context.getBean(BasicErrorController.class));
                    assertThat(resolved).isNotNull();
                    assertThat(resolved.getView()).isInstanceOf(PeekabootErrorView.class);
                });
    }

    /**
     * With no resolver of Peekaboot's and no static page, Boot falls back to the view name
     * {@code error} - the slot the default path fills.
     */
    @Test
    void theFallbackPathIsReachedThroughTheViewName() {
        contextRunner.run(context -> {
            ModelAndView resolved = resolve(context.getBean(BasicErrorController.class));
            assertThat(resolved.getViewName()).isEqualTo("error");
            assertThat(context.getBean("error")).isInstanceOf(PeekabootErrorView.class);
        });
    }

    /**
     * The override path never falls through to the view name, so an application's own error
     * bean and its own error template - either of which would beat the fallback path - must
     * still lose to the resolver here.
     */
    @Test
    void peekabootWinsOverAnApplicationErrorViewAndTemplate() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.override=true", "test.error-template=true")
                .withUserConfiguration(ErrorPageAutoConfigurationTest.OwnErrorView.class)
                .run(context -> {
                    ModelAndView resolved = resolve(context.getBean(BasicErrorController.class));
                    assertThat(resolved.getView()).isInstanceOf(PeekabootErrorView.class);
                });
    }

    /**
     * Flipping the resolver's {@code getOrder()} to {@code LOWEST_PRECEDENCE} would still pass
     * every other test here, because registering any {@code ErrorViewResolver} removes Boot's
     * own and nothing else is left to compete with it. This registers a second resolver one
     * step below {@code HIGHEST_PRECEDENCE}, so only a correct order lets Peekaboot's win.
     */
    @Test
    void peekabootOutranksACompetingResolverNearHighestPrecedence() {
        contextRunner
                .withPropertyValues("peekaboot.error-page.override=true")
                .withUserConfiguration(CompetingResolverConfiguration.class)
                .run(context -> {
                    ModelAndView resolved = resolve(context.getBean(BasicErrorController.class));
                    assertThat(resolved.getView()).isInstanceOf(PeekabootErrorView.class);
                });
    }

    /**
     * The resolver answers every status, not just the 500 every other test here resolves.
     * Registering any {@code ErrorViewResolver} removes Boot's own, so a status this one declined
     * would drop the {@code error/404.html} and {@code error/4xx.html} conventions rather than
     * fall through to them.
     */
    @Test
    void peekabootResolvesA404WithTheOverrideOn() {
        contextRunner.withPropertyValues("peekaboot.error-page.override=true").run(context -> {
            ModelAndView resolved = resolve(context.getBean(BasicErrorController.class), 404);
            assertThat(resolved).isNotNull();
            assertThat(resolved.getView()).isInstanceOf(PeekabootErrorView.class);
        });
    }

    private static MockHttpServletRequest errorRequest() {
        return errorRequest(500);
    }

    private static MockHttpServletRequest errorRequest(int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute("jakarta.servlet.error.status_code", status);
        return request;
    }

    private static ModelAndView resolve(BasicErrorController controller) {
        return resolve(controller, 500);
    }

    private static ModelAndView resolve(BasicErrorController controller, int status) {
        return controller.errorHtml(errorRequest(status), new MockHttpServletResponse());
    }

    @Configuration(proxyBeanMethods = false)
    static class CompetingResolverConfiguration {

        static final View MARKER =
                (model, request, response) -> response.getWriter().write("a competing resolver's page");

        @Bean
        ErrorViewResolver competingErrorViewResolver() {
            return new CompetingErrorViewResolver(MARKER);
        }
    }

    /** Mirrors the shape of {@link ErrorPageAutoConfiguration.PeekabootErrorViewResolver} at a lower precedence. */
    record CompetingErrorViewResolver(View view) implements ErrorViewResolver, Ordered {

        @Override
        public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
            return new ModelAndView(view, model);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 1;
        }
    }
}
