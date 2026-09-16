package org.peekaboot.autoconfigure.integration;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class TestController {

    @GetMapping(value = "/test", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String testPage() {
        return """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><h1>Test Page</h1></body>
            </html>
            """;
    }

    /** A servlet forward, see TomcatForwardResponseCustomizer. */
    @GetMapping("/forwarded")
    public String forwardedPage() {
        return "forward:/test";
    }

    @GetMapping(value = "/api/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String apiData() {
        return "{\"message\":\"hello\"}";
    }

    /** Always throws: the error dispatch's page and the bar on it are what ErrorDispatchIT reads. */
    @GetMapping("/throwing")
    public String throwing() {
        throw new IllegalStateException("gateway unreachable");
    }

    /** A non-GET failure: ErrorDispatchIT uses it to prove the bar reports the original method. */
    @PostMapping("/throwing-post")
    public String throwingPost() {
        throw new IllegalStateException("gateway unreachable");
    }
}
