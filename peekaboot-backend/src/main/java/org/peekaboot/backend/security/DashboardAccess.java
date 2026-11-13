package org.peekaboot.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Decides whether a request may reach Peekaboot's dashboard and API. */
public interface DashboardAccess {

    /** True when the request may proceed, false once this has answered the response itself. */
    boolean admit(HttpServletRequest request, HttpServletResponse response) throws IOException;

    /** Admits every request, for an application with no guard. */
    static DashboardAccess open() {
        return (request, response) -> true;
    }
}
