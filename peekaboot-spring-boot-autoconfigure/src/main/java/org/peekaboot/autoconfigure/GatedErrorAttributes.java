package org.peekaboot.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * The application's error attributes as Peekaboot's error page reads them: the exception, its
 * message and its stack trace go only to a request the access rule permits. Every other request
 * gets what Boot shows by default, the status, its reason and the path.
 */
final class GatedErrorAttributes implements ErrorAttributes {

    private final ErrorAttributes delegate;

    private final Predicate<HttpServletRequest> detailPermitted;

    GatedErrorAttributes(ErrorAttributes delegate, Predicate<HttpServletRequest> detailPermitted) {
        this.delegate = delegate;
        this.detailPermitted = detailPermitted;
    }

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        return delegate.getErrorAttributes(
                webRequest, permitsDetail(webRequest) ? options : ErrorAttributeOptions.defaults());
    }

    @Override
    public Throwable getError(WebRequest webRequest) {
        return delegate.getError(webRequest);
    }

    private boolean permitsDetail(WebRequest webRequest) {
        return webRequest instanceof ServletWebRequest servletRequest
                && detailPermitted.test(servletRequest.getRequest());
    }
}
