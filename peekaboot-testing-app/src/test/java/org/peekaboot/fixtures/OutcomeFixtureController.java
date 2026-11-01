package org.peekaboot.fixtures;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Answers with the outcome a test names, so a trace's verdict can be read without a real failure behind it. */
@RestController
public class OutcomeFixtureController {

    public static final String ERROR_LOG_PATH = "/fixtures/error-log";

    public static final String ERROR_LOG_MESSAGE = "fixture logged an error and answered 200";

    public static final String LOGGED_EXCEPTION_PATH = "/fixtures/logged-exception";

    public static final String LOGGED_THEN_THROWN_PATH = "/fixtures/logged-then-thrown";

    public static final String LOGGED_THEN_THROWN_MESSAGE = "fixture logged one exception and threw another";

    private static final Logger log = LoggerFactory.getLogger(OutcomeFixtureController.class);

    public static String statusPath(int status) {
        return "/fixtures/status/" + status;
    }

    @GetMapping(value = "/fixtures/status/{status}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> status(@PathVariable("status") int status) {
        return ResponseEntity.status(status).body("status " + status);
    }

    @GetMapping(value = ERROR_LOG_PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    public String errorLog() {
        log.error(ERROR_LOG_MESSAGE);
        return "logged";
    }

    @GetMapping(value = LOGGED_EXCEPTION_PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    public String loggedException() {
        log.error(ERROR_LOG_MESSAGE, new IllegalArgumentException("fixture cause"));
        return "logged";
    }

    /** The logged class differs from the thrown one, so a test can tell which of the two a trace names. */
    @GetMapping(value = LOGGED_THEN_THROWN_PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    public String loggedThenThrown() {
        log.error(LOGGED_THEN_THROWN_MESSAGE, new IllegalArgumentException("fixture cause"));
        throw new IllegalStateException("fixture failure");
    }
}
