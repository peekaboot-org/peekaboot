package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.lifecycle.ApplicationStoppedListener;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.ClassUtils;

/**
 * The security-relevant default, pinned once for every auto-configuration this module
 * registers: with no {@code peekaboot.enabled}, not one Peekaboot bean exists, whatever
 * else the application has. Detection is the only thing that ever sets that property, so
 * a {@code matchIfMissing} on any class would switch a dev tool on wherever detection does
 * not run.
 */
class PeekabootOffByDefaultTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(registeredAutoConfigurations()))
            // the beans the conditional halves look for, so nothing is off for lack of them
            .withBean(Tracer.class, () -> mock(Tracer.class))
            .withBean(ObservationRegistry.class, ObservationRegistry::create)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void registersNoPeekabootBeanWithoutAPeekabootProperty() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(peekabootBeans(context)).isEmpty();
        });
    }

    /**
     * {@code DevToolbarAutoConfiguration} carries a second switch without
     * {@code matchIfMissing}; with neither property set, either one masks a
     * {@code matchIfMissing} on the other. The toolbar switch alone must still yield nothing.
     */
    @Test
    void registersNoPeekabootBeanWithOnlyTheToolbarSwitchedOn() {
        contextRunner.withPropertyValues("peekaboot.dev-toolbar=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(peekabootBeans(context)).isEmpty();
        });
    }

    /** The control: the same context wires Peekaboot once the master switch is on. */
    @Test
    void theSameContextWiresPeekabootOnceEnabled() {
        try (LogCapture insightsStartup = LogCapture.attach(InsightsService.class);
                LogCapture stopBanner = LogCapture.attach(ApplicationStoppedListener.class)) {
            contextRunner.withPropertyValues("peekaboot.enabled=true").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(peekabootBeans(context)).isNotEmpty();
            });
        }
    }

    /** A class the registration file does not list is invisible to the two tests above, and to consumers. */
    @Test
    void everyAutoConfigurationInThisPackageIsRegistered() throws IOException {
        assertThat(Arrays.stream(registeredAutoConfigurations()).map(Class::getName))
                .containsExactlyInAnyOrderElementsOf(annotatedAutoConfigurations());
    }

    /**
     * Read from the class files, not scanned: Spring's component scanner evaluates each
     * class's {@code @Conditional}s, and every one of these fails without the property.
     */
    private List<String> annotatedAutoConfigurations() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readers = new SimpleMetadataReaderFactory(resolver);
        String packagePath =
                ClassUtils.convertClassNameToResourcePath(getClass().getPackageName());
        List<String> annotated = new ArrayList<>();
        for (Resource classFile : resolver.getResources("classpath*:" + packagePath + "/*.class")) {
            AnnotationMetadata metadata = readers.getMetadataReader(classFile).getAnnotationMetadata();
            if (metadata.hasAnnotation(AutoConfiguration.class.getName())) {
                annotated.add(metadata.getClassName());
            }
        }
        return annotated;
    }

    private static Class<?>[] registeredAutoConfigurations() {
        ClassLoader classLoader = PeekabootOffByDefaultTest.class.getClassLoader();
        List<Class<?>> classes = ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates().stream()
                .filter(name -> name.startsWith("org.peekaboot."))
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, classLoader))
                .toList();
        return classes.toArray(Class<?>[]::new);
    }

    private static List<String> peekabootBeans(ApplicationContext context) {
        return Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getName().startsWith("org.peekaboot.");
                })
                .toList();
    }
}
