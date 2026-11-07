package org.peekaboot.fixtures;

import org.peekaboot.testingapp.DigestFailure;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fails with a message carrying {@code tag}, on a route only ErrorDigestIT calls, so the IT counts its own failures alone. */
@RestController
public class DigestFixtureController {

    public static final String PATH = "/fixtures/digest-failure";

    @GetMapping(value = PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    public String fail(@RequestParam("tag") String tag) {
        throw DigestFailure.failure(tag);
    }
}
