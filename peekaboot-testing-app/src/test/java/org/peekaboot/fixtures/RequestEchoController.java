package org.peekaboot.fixtures;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Answers with what the server saw of the request it is serving, for a test that cannot see inside it. */
@RestController
public class RequestEchoController {

    public static final String MDC_KEYS_PATH = "/fixtures/mdc-keys";

    public static final String REMOTE_ADDRESS_PATH = "/fixtures/remote-address";

    /** The MDC keys set while the request is served: the names the application's tracing writes its ids under. */
    @GetMapping(value = MDC_KEYS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> mdcKeys() {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return context == null ? List.of() : List.copyOf(context.keySet());
    }

    @GetMapping(value = REMOTE_ADDRESS_PATH, produces = MediaType.TEXT_PLAIN_VALUE)
    public String remoteAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
