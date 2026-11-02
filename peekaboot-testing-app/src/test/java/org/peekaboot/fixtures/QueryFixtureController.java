package org.peekaboot.fixtures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers with the status a test names, after the pause it names, and logs one INFO line carrying the
 * test's tag. A query test finds its own traces by the tag in their path, and its own log lines by the
 * tag in their message, among everything the shared context's other tests add meanwhile.
 */
@RestController
public class QueryFixtureController {

    /** Caps a pause, so a typo cannot hold a request thread for minutes. */
    private static final long MAX_PAUSE_MS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(QueryFixtureController.class);

    /** Every path of {@code tag} starts with this, so it is the {@code path} filter that lists only them. */
    public static String pathPrefix(String tag) {
        return "/fixtures/query/" + tag + "/";
    }

    public static String path(String tag, int status) {
        return pathPrefix(tag) + status;
    }

    public static String path(String tag, int status, long pauseMs) {
        return path(tag, status) + "?pauseMs=" + pauseMs;
    }

    public static String routePath(int status, long pauseMs) {
        return "/fixtures/routes/" + status + "?pauseMs=" + pauseMs;
    }

    @GetMapping(value = "/fixtures/query/{tag}/{status}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> answer(
            @PathVariable("tag") String tag,
            @PathVariable("status") int status,
            @RequestParam(name = "pauseMs", defaultValue = "0") long pauseMs)
            throws InterruptedException {
        pause(pauseMs);
        log.info("query fixture {} answered {}", tag, status);
        return ResponseEntity.status(status).body(tag);
    }

    /** RouteStatsIT alone requests this route, so its row counts exactly the requests that test sends. */
    @GetMapping(value = "/fixtures/routes/{status}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> route(
            @PathVariable("status") int status, @RequestParam(name = "pauseMs", defaultValue = "0") long pauseMs)
            throws InterruptedException {
        pause(pauseMs);
        return ResponseEntity.status(status).body("route " + status);
    }

    private static void pause(long pauseMs) throws InterruptedException {
        Thread.sleep(Math.min(pauseMs, MAX_PAUSE_MS));
    }
}
