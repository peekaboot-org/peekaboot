package org.peekaboot.autoconfigure;

import java.util.List;
import org.peekaboot.backend.errorpage.PeekabootErrorView;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
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
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.View;

/**
 * Peekaboot's error page, in the one place Spring Boot leaves for a fallback: the view
 * named {@code error}. Boot's whitelabel view declares {@code @ConditionalOnMissingBean(name = "error")},
 * so registering this one first backs that one off, and the {@code BeanNameViewResolver}
 * Boot registers alongside resolves this bean instead. Every condition Boot puts on its own
 * whitelabel page is repeated here, so an application that brings any error page of its
 * own keeps it - except an application that excludes {@code ErrorMvcAutoConfiguration}
 * outright, which leaves no {@code ErrorAttributes} bean for either page to render with;
 * Boot's own whitelabel view cannot express that case either, since we run ahead of it.
 */
@AutoConfiguration(before = ErrorMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, ErrorAttributes.class})
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ERROR_PAGE_ENABLED)
@ConditionalOnBooleanProperty(name = "spring.web.error.whitelabel.enabled", matchIfMissing = true)
@Conditional(ErrorPageAutoConfiguration.ErrorTemplateMissingCondition.class)
public class ErrorPageAutoConfiguration {

    @Bean(name = "error")
    @ConditionalOnMissingBean(name = "error")
    public View peekabootErrorView(ErrorAttributes errorAttributes, BeanFactory beanFactory) {
        return new PeekabootErrorView(errorAttributes, applicationPackages(beanFactory));
    }

    /** The application's own packages, so its frames can be told from the framework's; empty where none are registered. */
    private static List<String> applicationPackages(BeanFactory beanFactory) {
        return AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of();
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
