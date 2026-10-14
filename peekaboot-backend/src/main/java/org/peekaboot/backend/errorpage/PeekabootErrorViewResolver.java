package org.peekaboot.backend.errorpage;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

/**
 * Peekaboot's page for every error {@code BasicErrorController} renders, ahead of a template, a
 * static {@code error/*.html} or the view named {@code error}. Registered only where
 * {@code peekaboot.error-page.override} is set. Resolves every error, so it must never return
 * null: registering any {@code ErrorViewResolver} removes Boot's own, and with it the
 * {@code error/4xx.html} convention a null would fall through to.
 */
public record PeekabootErrorViewResolver(PeekabootErrorView view) implements ErrorViewResolver, Ordered {

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        return new ModelAndView(view, model);
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }
}
