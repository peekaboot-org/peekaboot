package org.peekaboot.backend.security;

import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.problem.ApiProblem;
import org.springframework.http.HttpStatus;

/**
 * Admits only a request this machine sent to itself, for a local run that has no login in front
 * of it. A loopback client address alone does not prove that. A proxy or tunnel on this machine
 * has one whoever it speaks for, and so has this machine's browser while a page of another site,
 * or a name another site points at this machine, drives it.
 *
 * <p>The rule reads the container's own request. What the container itself rewrote before the
 * servlet API hands it out stays out of sight: under {@code server.forward-headers-strategy=native}
 * the client address is already the one a forwarding header claimed.
 */
public final class LoopbackOnlyAccess implements DashboardAccess {

    private static final ApiProblem REFUSED = ApiProblem.of(
            HttpStatus.FORBIDDEN,
            "On a local run Peekaboot answers only requests from this machine, with a loopback client address"
                    + " and Host header and no other site as their origin."
                    + " Set peekaboot.remote-access.enabled=true to open it to other clients.");

    private static final String HTTP_2 = "HTTP/2.0";
    private static final String LOCALHOST = "localhost";
    private static final String SCHEME_END = "://";
    private static final String CROSS_SITE = "cross-site";

    /** What a browser sends for a request of the page's own site, or one the user typed or bookmarked. */
    private static final Set<String> OWN_SITE = Set.of("same-origin", "same-site", "none");

    @Override
    public boolean admit(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (permits(request)) {
            return true;
        }
        Refusals.answer(request, response, REFUSED);
        return false;
    }

    @Override
    public boolean permitsDebugDetail(HttpServletRequest request) {
        return permits(request);
    }

    public boolean permits(HttpServletRequest request) {
        HttpServletRequest received = asTheContainerReceivedIt(request);
        return isLoopbackLiteral(received.getRemoteAddr())
                && isRelayedForNoOtherMachine(received)
                && namesThisMachine(requestedAuthority(received))
                && comesFromNoOtherSite(received);
    }

    /**
     * The request beneath every wrapper. A host that trusts forwarding headers wraps it in one that
     * answers with the address and host those headers claim and hides the headers, as Spring's
     * {@code ForwardedHeaderFilter} does.
     */
    private static HttpServletRequest asTheContainerReceivedIt(HttpServletRequest request) {
        HttpServletRequest received = request;
        while (received instanceof ServletRequestWrapper wrapper
                && wrapper.getRequest() instanceof HttpServletRequest wrapped) {
            received = wrapped;
        }
        return received;
    }

    private static boolean isRelayedForNoOtherMachine(HttpServletRequest request) {
        return request.getHeader("Forwarded") == null
                && Collections.list(request.getHeaders("X-Forwarded-For")).stream()
                        .flatMap(clients -> Arrays.stream(clients.split(",", -1)))
                        .allMatch(client -> isLoopbackLiteral(client.trim()));
    }

    /** HTTP/2 names the host in {@code :authority}, which the container hands on as the server name alone. */
    private static @Nullable String requestedAuthority(HttpServletRequest request) {
        String host = request.getHeader("Host");
        return host == null && HTTP_2.equals(request.getProtocol()) ? request.getServerName() : host;
    }

    private static boolean comesFromNoOtherSite(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !namesThisMachine(authorityOf(origin))) {
            return false;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        return site == null || OWN_SITE.contains(site) || (CROSS_SITE.equals(site) && followsALink(request));
    }

    /** A link followed from another site, which hands the linking page nothing. */
    private static boolean followsALink(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && "navigate".equals(request.getHeader("Sec-Fetch-Mode"))
                && "document".equals(request.getHeader("Sec-Fetch-Dest"));
    }

    /** Null for an origin that names no host, the opaque {@code null} among them. */
    private static @Nullable String authorityOf(String origin) {
        int scheme = origin.indexOf(SCHEME_END);
        return scheme < 0 ? null : origin.substring(scheme + SCHEME_END.length());
    }

    /** Whether a {@code Host} header, or the host and port of an origin, names this machine. */
    private static boolean namesThisMachine(@Nullable String authority) {
        if (authority == null) {
            return false;
        }
        String host = hostOf(authority);
        return LOCALHOST.equals(host) || host.endsWith("." + LOCALHOST) || isLoopbackLiteral(host);
    }

    /** The host without its port, its brackets and a trailing dot, in lower case. */
    private static String hostOf(String authority) {
        String host;
        if (authority.startsWith("[")) {
            int closing = authority.indexOf(']');
            host = closing < 0 ? "" : authority.substring(1, closing);
        } else {
            int port = authority.indexOf(':');
            host = port < 0 ? authority : authority.substring(0, port);
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    /** Parsed, never resolved. Whoever sent the request would pick the answer to a lookup. */
    private static boolean isLoopbackLiteral(@Nullable String address) {
        if (address == null) {
            return false;
        }
        int zone = address.indexOf('%');
        try {
            return InetAddress.ofLiteral(zone < 0 ? address : address.substring(0, zone))
                    .isLoopbackAddress();
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
    }
}
