package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ApplicationBeansTest {

    /**
     * Boot's how-to for a second DataSource marks it this way, and plain Map injection drops it. A
     * bean that opted out of autowiring altogether stays out, unlike with getBeansOfType.
     */
    @Test
    void findsABeanThatOptedOutOfDefaultInjection() {
        new ApplicationContextRunner()
                .withUserConfiguration(Widgets.class)
                .run(context -> assertThat(ApplicationBeans.ofType(context.getBeanFactory(), Widget.class))
                        .containsOnlyKeys("primaryWidget", "secondaryWidget"));
    }

    record Widget(String name) {}

    @Configuration(proxyBeanMethods = false)
    static class Widgets {

        @Bean
        Widget primaryWidget() {
            return new Widget("primary");
        }

        @Bean(defaultCandidate = false)
        Widget secondaryWidget() {
            return new Widget("secondary");
        }

        @Bean(autowireCandidate = false)
        Widget hiddenWidget() {
            return new Widget("hidden");
        }
    }
}
