package org.peekaboot.autoconfigure.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The application under test on its random port, asked the way a browser would ask. */
final class LocalServer {

    /** The blob ToolbarShell embeds for toolbar.js, which reads it before it calls home. */
    private static final Pattern TOOLBAR_DATA =
            Pattern.compile("<script id=\"peekaboot-toolbar-data\" type=\"application/json\">(.*?)</script>");

    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private static final String CRLF = "\r\n";

    private final int port;

    private final RestClient client;

    LocalServer(int port) {
        this.port = port;
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    /** A client that hands back every response, error statuses included, for the test to assert on. */
    RestClient client() {
        return client;
    }

    /** The body of a GET that accepts HTML. */
    String html(String uri) {
        return client.get().uri(uri).accept(MediaType.TEXT_HTML).retrieve().body(String.class);
    }

    /**
     * One GET on a connection of its own, answered as status line, headers and body in one
     * string. For a request that names a Host of its choice, which the JDK's clients refuse to send.
     */
    String rawGet(String path, Map<String, String> headers) {
        StringBuilder request =
                new StringBuilder("GET ").append(path).append(" HTTP/1.1").append(CRLF);
        headers.forEach(
                (name, value) -> request.append(name).append(": ").append(value).append(CRLF));
        request.append("Connection: close").append(CRLF).append(CRLF);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
            return unchunked(socket.getInputStream().readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The response without the framing of a chunked body. Tomcat chunks a page it did not
     * buffer, and a chunk size in the middle of the body would split what a test looks for.
     */
    private static String unchunked(byte[] response) {
        // one char per byte, so a chunk's size in bytes counts chars
        String framed = new String(response, StandardCharsets.ISO_8859_1);
        int bodyStart = framed.indexOf(CRLF + CRLF) + 2 * CRLF.length();
        String head = framed.substring(0, bodyStart);
        if (!head.contains("Transfer-Encoding: chunked")) {
            return new String(response, StandardCharsets.UTF_8);
        }
        StringBuilder unframed = new StringBuilder(head);
        int chunkStart = bodyStart;
        int size;
        do {
            int sizeEnd = framed.indexOf(CRLF, chunkStart);
            size = Integer.parseInt(framed.substring(chunkStart, sizeEnd), 16);
            int dataStart = sizeEnd + CRLF.length();
            unframed.append(framed, dataStart, dataStart + size);
            chunkStart = dataStart + size + CRLF.length();
        } while (size > 0);
        return new String(unframed.toString().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /** The toolbar data blob in an injected page, parsed, so a blob that is no valid JSON fails. */
    static JsonNode toolbarData(String html) {
        Matcher matcher = TOOLBAR_DATA.matcher(html);
        assertThat(matcher.find())
                .as("the injected page carries the toolbar data blob")
                .isTrue();
        return JsonMapper.builder().build().readTree(matcher.group(1));
    }
}
