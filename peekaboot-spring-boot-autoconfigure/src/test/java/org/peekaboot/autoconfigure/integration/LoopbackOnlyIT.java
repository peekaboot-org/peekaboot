package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.autoconfigure.DetectedLaunches;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * A local run without the guard on a real container: the dashboard, the API, the bar and the
 * error page's detail go to this machine only. Every request here arrives from loopback, so what
 * tells them apart is what a browser led to another address would send.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
@ContextConfiguration(initializers = DetectedLaunches.LocalDevelopment.class)
class LoopbackOnlyIT {

    private static final String THIS_MACHINE = "localhost";

    private static final String ANOTHER_HOST = "evil.example";

    /** Only the loopback rule's refusal names it, so a 403 from anything else does not pass for one. */
    private static final String THE_SWITCH = "peekaboot.remote-access.enabled";

    /** The markup of a rendered frame. The class name alone is in the stylesheet every error page inlines. */
    private static final String STACK_FRAME = "<span class=\"pk-stack-trace__frame";

    @LocalServerPort
    private int port;

    private LocalServer server;

    @BeforeEach
    void setUp() {
        server = new LocalServer(port);
    }

    @Test
    void aRequestFromThisMachineReachesTheApi() {
        assertThat(server.rawGet("/peekaboot/api/features", Map.of("Host", THIS_MACHINE)))
                .startsWith("HTTP/1.1 200");
    }

    @Test
    void aRequestNamingAnotherHostIsRefused() {
        assertThat(server.rawGet("/peekaboot/api/features", Map.of("Host", ANOTHER_HOST)))
                .startsWith("HTTP/1.1 403")
                .contains("application/problem+json")
                .contains(THE_SWITCH);
    }

    @Test
    void aCrossSiteRequestIsRefused() {
        Map<String, String> crossSite =
                Map.of("Host", THIS_MACHINE, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "cors");

        assertThat(server.rawGet("/peekaboot/api/features", crossSite))
                .startsWith("HTTP/1.1 403")
                .contains(THE_SWITCH);
    }

    @Test
    void aForeignOriginIsRefused() {
        Map<String, String> foreignOrigin = Map.of("Host", THIS_MACHINE, "Origin", "http://" + ANOTHER_HOST);

        assertThat(server.rawGet("/peekaboot/api/features", foreignOrigin))
                .startsWith("HTTP/1.1 403")
                .contains(THE_SWITCH);
    }

    @Test
    void theUiIsRefusedToo() {
        assertThat(server.rawGet("/peekaboot/ui/dashboard/main.js", Map.of("Host", ANOTHER_HOST)))
                .startsWith("HTTP/1.1 403")
                .contains(THE_SWITCH);
    }

    @Test
    void aPageServedToAnotherHostCarriesNoToolbar() {
        assertThat(page("/test", ANOTHER_HOST)).contains("Test Page").doesNotContain("peekaboot-toolbar-data");

        assertThat(page("/test", THIS_MACHINE)).contains("Test Page").contains("peekaboot-toolbar-data");
    }

    @Test
    void theErrorPageKeepsItsDetailOnThisMachine() {
        assertThat(page("/throwing", ANOTHER_HOST))
                .startsWith("HTTP/1.1 500")
                .contains("pk-error__status")
                .doesNotContain("java.lang.IllegalStateException", "gateway unreachable", STACK_FRAME)
                .doesNotContain("peekaboot-toolbar-data");

        assertThat(page("/throwing", THIS_MACHINE))
                .startsWith("HTTP/1.1 500")
                .contains("java.lang.IllegalStateException", "gateway unreachable", STACK_FRAME);
    }

    /** A page asked for the way a browser asks, under the host name it was given. */
    private String page(String path, String host) {
        return server.rawGet(path, Map.of("Host", host, "Accept", "text/html"));
    }
}
