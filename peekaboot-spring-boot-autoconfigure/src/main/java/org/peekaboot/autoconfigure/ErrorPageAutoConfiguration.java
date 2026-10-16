package org.peekaboot.autoconfigure;

import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.errorpage.PeekabootErrorExceptionResolver;
import org.peekaboot.backend.errorpage.PeekabootErrorView;
import org.peekaboot.backend.errorpage.PeekabootErrorViewResolver;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProvider;
import org.springframework.boot.autoconfigure.template.TemplateAvailabilityProviders;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Peekaboot's error page, in the fallback slot Spring Boot leaves for one, or - where
 * {@code peekaboot.error-page.override} is set - in either of two places that outrank an
 * application's own. By default it fills the fallback slot - the view named {@code error}, which
 * Boot's whitelabel view claims with {@code @ConditionalOnMissingBean(name = "error")} - under
 * every condition Boot puts on that view, so an application that brings any error page of its own
 * keeps it. With the override set, it registers a high-precedence {@link ErrorViewResolver},
 * which {@code BasicErrorController} consults before it reaches a template, a static
 * {@code error/*.html} or the view name {@code error} at all, and a high-precedence
 * {@code HandlerExceptionResolver}, which outranks {@code ExceptionHandlerExceptionResolver} and
 * so beats an application's own {@code @ControllerAdvice} - the one case the view resolver alone
 * cannot reach, because that advice resolves inside the REQUEST dispatch and no ERROR dispatch
 * ever follows it. Neither override path can turn the page on by itself; both sit behind
 * {@code peekaboot.error-page.enabled}. Neither covers an application that excludes
 * {@code ErrorMvcAutoConfiguration} outright, which leaves no {@code ErrorAttributes} bean for
 * any page to render with, or one whose own error page is an {@code ErrorController} on
 * {@code /error}, which backs {@code BasicErrorController} off entirely and with it anything
 * that would consult an {@code ErrorViewResolver}.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, ErrorAttributes.class})
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ERROR_PAGE_ENABLED)
@Import(StackTraceRulesConfiguration.class)
public class ErrorPageAutoConfiguration {

    @Bean(name = "error")
    @ConditionalOnMissingBean(name = "error")
    @ConditionalOnBooleanProperty(
            name = PeekabootPropertyKeys.ERROR_PAGE_OVERRIDE,
            havingValue = false,
            matchIfMissing = true)
    @ConditionalOnBooleanProperty(name = "spring.web.error.whitelabel.enabled", matchIfMissing = true)
    @Conditional(ErrorPageAutoConfiguration.ErrorTemplateMissingCondition.class)
    public PeekabootErrorView peekabootErrorView(
            ErrorAttributes errorAttributes, StackTraceRules stackTraceRules, PeekabootPaths paths) {
        return new PeekabootErrorView(errorAttributes, stackTraceRules, paths);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.ERROR_PAGE_OVERRIDE)
    public PeekabootErrorViewResolver peekabootErrorViewResolver(
            ErrorAttributes errorAttributes, StackTraceRules stackTraceRules, PeekabootPaths paths) {
        return new PeekabootErrorViewResolver(new PeekabootErrorView(errorAttributes, stackTraceRules, paths));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(PeekabootPropertyKeys.ERROR_PAGE_OVERRIDE)
    public PeekabootErrorExceptionResolver peekabootErrorExceptionResolver(
            ErrorAttributes errorAttributes,
            StackTraceRules stackTraceRules,
            PeekabootPaths paths,
            MessageSource messageSource) {
        return new PeekabootErrorExceptionResolver(
                new PeekabootErrorView(errorAttributes, stackTraceRules, paths), messageSource);
    }

    /**
     * Mirrors ErrorMvcAutoConfiguration's own package-private ErrorTemplateMissingCondition:
     * a view template named {@code error} is the application's own error page, and it resolves
     * ahead of any view bean.
     */
    static final class ErrorTemplateMissingCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ConditionMessage.Builder message = ConditionMessage.forCondition("Peekaboot ErrorTemplate Missing");
            ClassLoader classLoader = context.getClassLoader();
            TemplateAvailabilityProvider provider = new TemplateAvailabilityProviders(classLoader)
                    .getProvider("error", context.getEnvironment(), classLoader, context.getResourceLoader());
            return provider != null
                    ? ConditionOutcome.noMatch(message.foundExactly("template from " + provider))
                    : ConditionOutcome.match(
                            message.didNotFind("error template view").atAll());
        }
    }
}
