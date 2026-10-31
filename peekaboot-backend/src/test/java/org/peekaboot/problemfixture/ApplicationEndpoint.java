package org.peekaboot.problemfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A host application's controller, outside {@code org.peekaboot.backend} so Peekaboot's advice must leave it alone. */
@RestController
public class ApplicationEndpoint {

    @GetMapping("/app/test")
    String get(@RequestParam("limit") int limit) {
        return "ok";
    }
}
