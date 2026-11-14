package org.peekaboot.backend.tracing.capture;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;

/** Records whether the application asked the container for parameters or parts, which is what parses a form body. */
final class ParameterReads extends HttpServletRequestWrapper {

    // an async dispatch reads on another thread
    private volatile boolean parametersRead;

    ParameterReads(HttpServletRequest request) {
        super(request);
    }

    boolean parametersRead() {
        return parametersRead;
    }

    @Override
    public String getParameter(String name) {
        parametersRead = true;
        return super.getParameter(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        parametersRead = true;
        return super.getParameterMap();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        parametersRead = true;
        return super.getParameterNames();
    }

    @Override
    public String[] getParameterValues(String name) {
        parametersRead = true;
        return super.getParameterValues(name);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        parametersRead = true;
        return super.getParts();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        parametersRead = true;
        return super.getPart(name);
    }
}
