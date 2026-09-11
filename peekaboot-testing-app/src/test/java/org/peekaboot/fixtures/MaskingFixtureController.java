package org.peekaboot.fixtures;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints that take a secret-bearing query parameter and a secret-bearing form field, so
 * {@code RequestAndQueryMaskingIT} can drive real masking over a real request. The sample app
 * ships none: a demo application with a {@code password} form field would be the wrong example.
 */
@RestController
public class MaskingFixtureController {

    @GetMapping("/masking-test/search")
    String search(
            @RequestParam(name = "api_key", required = false) String apiKey, @RequestParam(required = false) String q) {
        return "ok";
    }

    @PostMapping(value = "/masking-test/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    String login(@RequestParam String username, @RequestParam String password) {
        return "ok";
    }
}
