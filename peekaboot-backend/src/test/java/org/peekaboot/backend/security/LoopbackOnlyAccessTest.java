package org.peekaboot.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.peekaboot.backend.testsupport.Requests.request;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.config.PeekabootJson;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import tools.jackson.databind.JsonNode;

class LoopbackOnlyAccessTest {

    private static final String REFUSAL_DETAIL = "On a local run Peekaboot answers only requests from this machine,"
            + " with a loopback client address and Host header and no other site as their origin."
            + " Set peekaboot.remote-access.enabled=true to open it to other clients.";

    private static final String PATH = "/peekaboot/api/features";

    private final LoopbackOnlyAccess access = new LoopbackOnlyAccess();

    @ParameterizedTest
    @CsvSource({
        "127.0.0.1, localhost:8080",
        "::1, [::1]:8080",
        "0:0:0:0:0:0:0:1, localhost",
        "127.0.0.5, 127.0.0.1",
        "::ffff:127.0.0.1, localhost",
        "127.0.0.1, app.localhost:8080",
        "127.0.0.1, LOCALHOST.",
        // no machine has an interface of that name, so only an address read without its zone parses
        "::1%nowhere, localhost",
    })
    void permitsARequestFromThisMachine(String remoteAddress, String host) {
        assertThat(access.permits(get(remoteAddress, host))).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"192.168.1.20", "10.0.0.5", "::ffff:10.0.0.5", "fe80::1%eth0", "localhost", ""})
    void refusesAClientAddressOffThisMachine(@Nullable String remoteAddress) {
        assertThat(access.permits(get(remoteAddress, "localhost"))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "evil.example",
                "localhost.evil.example",
                "notlocalhost",
                "127.0.0.1.evil.example",
                "0.0.0.0:8080",
                "192.168.1.20:8080",
                "[::1",
                ""
            })
    void refusesAHostHeaderThatNamesAnotherHost(@Nullable String host) {
        assertThat(access.permits(get("127.0.0.1", host))).isFalse();
    }

    /** Tomcat hands an HTTP/2 request's {@code :authority} on as the server name and adds no Host header. */
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "[::1]", "app.localhost"})
    void permitsAnHttp2RequestThatNamesThisMachine(String authority) {
        assertThat(access.permits(overHttp2(authority))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "192.168.1.20"})
    void refusesAnHttp2RequestThatNamesAnotherHost(String authority) {
        assertThat(access.permits(overHttp2(authority))).isFalse();
    }

    @ParameterizedTest
    @MethodSource("crossSiteRequests")
    void refusesACrossSiteRequest(MockHttpServletRequest crossSite) {
        assertThat(access.permits(crossSite)).isFalse();
    }

    /** The link hands the linking page nothing, and a dashboard nobody can link to is no use. */
    @Test
    void permitsALinkFollowedFromAnotherSite() {
        assertThat(access.permits(fetched("GET", "cross-site", "navigate", "document")))
                .isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "Sec-Fetch-Site, same-origin",
        "Sec-Fetch-Site, same-site",
        "Sec-Fetch-Site, none",
        "Origin, http://localhost:3000",
        "Origin, http://127.0.0.1:3000",
        "Origin, http://[::1]:3000",
    })
    void permitsTheBrowsersOwnRequests(String header, String value) {
        assertThat(access.permits(local(header, value))).isTrue();
    }

    /** A reverse proxy or tunnel on this machine has a loopback address of its own, whoever it speaks for. */
    @ParameterizedTest
    @CsvSource({
        "X-Forwarded-For, 203.0.113.9",
        "X-Forwarded-For, '127.0.0.1, 203.0.113.9'",
        "X-Forwarded-For, '127.0.0.1,'",
        "Forwarded, for=203.0.113.9",
        "Forwarded, for=127.0.0.1",
    })
    void refusesARequestRelayedForAnotherMachine(String header, String value) {
        assertThat(access.permits(local(header, value))).isFalse();
    }

    @Test
    void refusesARelayThatNamesTheOtherMachineInASecondHeader() {
        MockHttpServletRequest relayed = local("X-Forwarded-For", "127.0.0.1");
        relayed.addHeader("X-Forwarded-For", "203.0.113.9");

        assertThat(access.permits(relayed)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1, 127.0.0.1"})
    void permitsARelayOfThisMachinesOwnRequest(String forwardedFor) {
        assertThat(access.permits(local("X-Forwarded-For", forwardedFor))).isTrue();
    }

    /** The filter answers with the address the header claims and hides the header from whatever follows it. */
    @Test
    void refusesAnotherMachineThatClaimsToRelayThisOne() throws Exception {
        MockHttpServletRequest claiming = get("192.168.1.50", "localhost:8080");
        claiming.addHeader("X-Forwarded-For", "127.0.0.1");

        assertThat(access.permits(behindForwardedHeaderFilter(claiming))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "X-Forwarded-For, 203.0.113.9",
        "X-Forwarded-For, '127.0.0.1, 203.0.113.9'",
        "Forwarded, for=203.0.113.9",
        "Forwarded, for=127.0.0.1",
    })
    void refusesARelayedRequestBehindAForwardedHeaderFilter(String header, String value) throws Exception {
        assertThat(access.permits(behindForwardedHeaderFilter(local(header, value))))
                .isFalse();
    }

    /** The filter answers with the forwarded host where the rule asks an HTTP/2 request for its server name. */
    @Test
    void refusesAnHttp2RequestThatNamesAnotherHostBehindAForwardedHeaderFilter() throws Exception {
        MockHttpServletRequest rebound = overHttp2("evil.example");
        rebound.addHeader("X-Forwarded-Host", "localhost");

        assertThat(access.permits(behindForwardedHeaderFilter(rebound))).isFalse();
    }

    @Test
    void permitsARelayOfThisMachinesOwnRequestBehindAForwardedHeaderFilter() throws Exception {
        assertThat(access.permits(behindForwardedHeaderFilter(local("X-Forwarded-For", "127.0.0.1"))))
                .isTrue();
    }

    @Test
    void aRefusalNamesTheSwitchInAProblemBody() throws Exception {
        var response = new MockHttpServletResponse();

        boolean admitted = access.admit(get("10.0.0.5", "localhost"), response);

        assertThat(admitted).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode problem = PeekabootJson.MAPPER.readTree(response.getContentAsByteArray());
        assertThat(problem.path("status").asInt()).isEqualTo(403);
        assertThat(problem.path("title").asString("")).isEqualTo("Forbidden");
        assertThat(problem.path("detail").asString("")).isEqualTo(REFUSAL_DETAIL);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    /** The body would land inside the including page. */
    @Test
    void aRefusedIncludeGetsNoBody() throws Exception {
        var response = new MockHttpServletResponse();
        MockHttpServletRequest included = get("10.0.0.5", "localhost");
        included.setDispatcherType(DispatcherType.INCLUDE);

        boolean admitted = access.admit(included, response);

        assertThat(admitted).isFalse();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void aPermittedRequestIsAdmittedUntouched() throws Exception {
        var response = new MockHttpServletResponse();

        boolean admitted = access.admit(get("127.0.0.1", "localhost"), response);

        assertThat(admitted).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeaderNames()).isEmpty();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void debugDetailGoesOnlyToARequestItPermits() {
        assertThat(access.permitsDebugDetail(get("127.0.0.1", "localhost"))).isTrue();
        assertThat(access.permitsDebugDetail(get("10.0.0.5", "localhost"))).isFalse();
        assertThat(access.permitsDebugDetail(get("127.0.0.1", "evil.example"))).isFalse();
    }

    static Stream<Named<MockHttpServletRequest>> crossSiteRequests() {
        return Stream.of(
                named("a fetch from another site", fetched("GET", "cross-site", "cors", "empty")),
                named("a form another site posts", fetched("POST", "cross-site", "navigate", "document")),
                named("a frame on another site", fetched("GET", "cross-site", "navigate", "iframe")),
                named("a fetch that claims a document", fetched("GET", "cross-site", "no-cors", "document")),
                named("a site this rule does not know", fetched("GET", "cross-origin", "navigate", "document")),
                named("another site's Origin", local("Origin", "http://evil.example")),
                named("an opaque Origin", local("Origin", "null")));
    }

    private static MockHttpServletRequest get(@Nullable String remoteAddress, @Nullable String host) {
        MockHttpServletRequest request = request("GET", PATH);
        request.setRemoteAddr(remoteAddress);
        if (host != null) {
            request.addHeader("Host", host);
        }
        return request;
    }

    /** The request as a host with {@code server.forward-headers-strategy=framework} hands it on. */
    private static HttpServletRequest behindForwardedHeaderFilter(MockHttpServletRequest received) throws Exception {
        MockFilterChain handedOn = new MockFilterChain();
        new ForwardedHeaderFilter().doFilter(received, new MockHttpServletResponse(), handedOn);
        HttpServletRequest wrapped = (HttpServletRequest) handedOn.getRequest();
        assertThat(wrapped).isNotSameAs(received);
        return wrapped;
    }

    private static MockHttpServletRequest overHttp2(String authority) {
        MockHttpServletRequest request = get("127.0.0.1", null);
        request.setProtocol("HTTP/2.0");
        request.setServerName(authority);
        return request;
    }

    /** A request this machine sent to itself, plus one header. */
    private static MockHttpServletRequest local(String header, String value) {
        MockHttpServletRequest request = get("127.0.0.1", "localhost:8080");
        request.addHeader(header, value);
        return request;
    }

    /** A request this machine's browser sent, with the fetch metadata it attaches. */
    private static MockHttpServletRequest fetched(String method, String site, String mode, String destination) {
        MockHttpServletRequest request = get("127.0.0.1", "localhost:8080");
        request.setMethod(method);
        request.addHeader("Sec-Fetch-Site", site);
        request.addHeader("Sec-Fetch-Mode", mode);
        request.addHeader("Sec-Fetch-Dest", destination);
        return request;
    }
}
