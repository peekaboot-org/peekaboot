package org.peekaboot.autoconfigure.integration;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping(value = "/api/data", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String apiData() {
        return "{\"message\":\"hello\"}";
    }
}
