package org.peekaboot.backend.testsupport;

import org.springframework.mock.web.MockHttpServletRequest;

/** Builds the requests the servlet filter tests send. */
public final class Requests {

    private Requests() {}

    /** A GET without a context path: the request URI and the container's mapped path coincide. */
    public static MockHttpServletRequest get(String path) {
        return request("GET", path);
    }

    /** {@link #get(String)} with another method. */
    public static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
