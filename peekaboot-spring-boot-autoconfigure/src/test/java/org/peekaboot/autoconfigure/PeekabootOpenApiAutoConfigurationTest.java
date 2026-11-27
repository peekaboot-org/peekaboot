package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.autoconfigure.PeekabootOpenApiAutoConfiguration.PeekabootControllersHiddenFromOpenApi;
import org.peekaboot.backend.controller.PeekabootController;
import org.peekaboot.backend.controller.TraceController;
import org.springdoc.api.AbstractOpenApiResource;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RestController;

/**
 * springdoc keeps the hidden controllers in a JVM-wide list it never empties, so each test starts
 * from an empty one and puts back what was there, or the first test to run would pass the rest.
 */
class PeekabootOpenApiAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class,
                    PeekabootOpenApiAutoConfiguration.class))
            .withBean(HostController.class)
            .withPropertyValues("peekaboot.enabled=true");

    private final List<Class<?>> hiddenBefore = new ArrayList<>();

    @BeforeEach
    void startWithNoControllerHidden() {
        hiddenBefore.addAll(springdocsHiddenControllers());
        springdocsHiddenControllers().clear();
    }

    @AfterEach
    void restoreTheControllersHiddenBefore() {
        springdocsHiddenControllers().clear();
        springdocsHiddenControllers().addAll(hiddenBefore);
    }

    @Test
    void hidesPeekabootsControllersAndNoneOfTheHostsOwn() {
        contextRunner.run(context -> {
            assertThat(AbstractOpenApiResource.isHiddenRestControllers(PeekabootController.class))
                    .isTrue();
            assertThat(AbstractOpenApiResource.isHiddenRestControllers(TraceController.class))
                    .isTrue();
            assertThat(AbstractOpenApiResource.isHiddenRestControllers(HostController.class))
                    .isFalse();
        });
    }

    /** Nothing asks for a bean here, so only an eager bean can do the hiding. */
    @Test
    void hidesPeekabootsControllersUnderLazyInitialization() {
        contextRunner
                .withInitializer(context ->
                        context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> {
                    assertThat(AbstractOpenApiResource.isHiddenRestControllers(PeekabootController.class))
                            .isTrue();
                    assertThat(AbstractOpenApiResource.isHiddenRestControllers(HostController.class))
                            .isFalse();
                });
    }

    @Test
    void staysOutWithoutSpringdoc() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(SpringDocUtils.class))
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootControllersHiddenFromOpenApi.class));
    }

    @Test
    void staysOutWhilePeekabootIsOff() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PeekabootControllersHiddenFromOpenApi.class));
    }

    @SuppressWarnings("unchecked")
    private static List<Class<?>> springdocsHiddenControllers() {
        return (List<Class<?>>) ReflectionTestUtils.getField(AbstractOpenApiResource.class, "HIDDEN_REST_CONTROLLERS");
    }

    /** Lives under {@code org.peekaboot} like the testing app's controllers do. */
    @RestController
    static class HostController {}
}
