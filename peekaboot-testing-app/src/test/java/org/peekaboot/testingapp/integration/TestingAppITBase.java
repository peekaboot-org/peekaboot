package org.peekaboot.testingapp.integration;

import org.peekaboot.testingapp.TestingAppTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * An integration test against the shared testing-app context, with Peekaboot's HTTP API on its
 * port. A class on a context of its own redeclares {@code @SpringBootTest} over this one.
 */
@TestingAppTest
abstract class TestingAppITBase {

    protected int port;

    protected PeekabootApi api;

    protected TraceApiClient traces;

    /** Injected with the test instance, so a PER_CLASS {@code @BeforeAll} has the clients too. */
    @LocalServerPort
    void connect(int port) {
        this.port = port;
        traces = new TraceApiClient(port);
        api = traces.api();
    }
}
