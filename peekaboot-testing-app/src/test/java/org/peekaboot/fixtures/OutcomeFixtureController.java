package org.peekaboot.fixtures;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Answers with the outcome a test names, so a trace's verdict can be read without a real failure behind it. */
@RestController
public class OutcomeFixtureController {

    public static String statusPath(int status) {
        return "/fixtures/status/" + status;
    }

    @GetMapping(value = "/fixtures/status/{status}", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> status(@PathVariable("status") int status) {
        return ResponseEntity.status(status).body("status " + status);
    }
}
