package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import java.util.concurrent.atomic.AtomicReference;

/** A real request held at its route until the test lets it through; see {@link PlaywrightTestBase#parkFirst}. */
final class ParkedRequest {

    private final Page page;
    private final AtomicReference<Route> route = new AtomicReference<>();

    ParkedRequest(Page page) {
        this.page = page;
    }

    void park(Route parked) {
        route.set(parked);
    }

    /** Waits until the request has reached its route. */
    void awaitParked() {
        page.waitForCondition(this::isParked);
    }

    boolean isParked() {
        return route.get() != null;
    }

    /** Lets the request through to the server, whose real response then answers it. */
    void resume() {
        Route parked = route.getAndSet(null);
        if (parked == null) {
            throw new IllegalStateException("no request is parked");
        }
        parked.resume();
    }
}
