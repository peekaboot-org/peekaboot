package org.peekaboot.backend.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class ContentBufferingResponseWrapperTest {

    MockHttpServletResponse originalResponse;
    ContentBufferingResponseWrapper wrapper;

    @BeforeEach
    void setUp() {
        originalResponse = new MockHttpServletResponse();
        wrapper = new ContentBufferingResponseWrapper(originalResponse);
    }

    @Test
    void shouldBufferOutputStreamContent() throws IOException {
        ServletOutputStream outputStream = wrapper.getOutputStream();
        outputStream.write("Hello World".getBytes(StandardCharsets.UTF_8));

        byte[] content = wrapper.getContentAsByteArray();
        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("Hello World");
        assertThat(originalResponse.getContentAsByteArray()).isEmpty();
    }

    @Test
    void shouldBufferWriterContent() throws IOException {
        PrintWriter writer = wrapper.getWriter();
        writer.write("Hello Writer");
        writer.flush();

        String content = wrapper.getContentAsString();
        assertThat(content).isEqualTo("Hello Writer");
    }

    @Test
    void shouldReturnContentAsString() throws IOException {
        wrapper.getWriter().write("Test Content");
        wrapper.flushBuffer();

        assertThat(wrapper.getContentAsString()).isEqualTo("Test Content");
    }

    @Test
    void shouldCopyBufferedContentToOriginalResponse() throws IOException {
        wrapper.getWriter().write("Buffered Content");
        wrapper.flushBuffer();
        wrapper.copyBodyToResponse();

        assertThat(originalResponse.getContentAsString()).isEqualTo("Buffered Content");
        assertThat(originalResponse.getContentLength()).isEqualTo("Buffered Content".length());
        assertThat(wrapper.isCommitted()).isTrue();
    }

    @Test
    void shouldCopyModifiedContentToResponse() throws IOException {
        byte[] modifiedContent = "Modified Content".getBytes(StandardCharsets.UTF_8);
        wrapper.copyBodyToResponse(modifiedContent);

        assertThat(originalResponse.getContentAsString()).isEqualTo("Modified Content");
        assertThat(originalResponse.getContentLength()).isEqualTo(modifiedContent.length);
        assertThat(wrapper.isCommitted()).isTrue();
    }

    @Test
    void shouldPreventGetWriterAfterGetOutputStream() throws IOException {
        wrapper.getOutputStream();

        assertThatThrownBy(() -> wrapper.getWriter())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("getOutputStream() has already been called");
    }

    @Test
    void shouldPreventGetOutputStreamAfterGetWriter() throws IOException {
        wrapper.getWriter();

        assertThatThrownBy(() -> wrapper.getOutputStream())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("getWriter() has already been called");
    }

    @Test
    void shouldReturnSameOutputStreamOnMultipleCalls() throws IOException {
        ServletOutputStream first = wrapper.getOutputStream();
        ServletOutputStream second = wrapper.getOutputStream();

        assertThat(first).isSameAs(second);
    }

    @Test
    void shouldReturnSameWriterOnMultipleCalls() throws IOException {
        PrintWriter first = wrapper.getWriter();
        PrintWriter second = wrapper.getWriter();

        assertThat(first).isSameAs(second);
    }

    @Test
    void shouldResetBuffer() throws IOException {
        wrapper.getWriter().write("Initial Content");
        wrapper.flushBuffer();
        wrapper.resetBuffer();

        assertThat(wrapper.getContentAsString()).isEmpty();
    }

    @Test
    void shouldNotBeCommittedInitially() {
        assertThat(wrapper.isCommitted()).isFalse();
    }

    @Test
    void nonHtmlContentTypeSwitchesToPassthrough() throws IOException {
        wrapper.setContentType("application/json");
        wrapper.getOutputStream().write("{\"id\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.isPassthrough()).isTrue();
        assertThat(originalResponse.getContentAsString()).isEqualTo("{\"id\":1}");
    }

    @Test
    void eventStreamContentTypeSwitchesToPassthrough() throws IOException {
        wrapper.setContentType("text/event-stream");
        wrapper.getOutputStream().write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
        wrapper.flushBuffer();

        assertThat(wrapper.isPassthrough()).isTrue();
        assertThat(originalResponse.getContentAsString()).isEqualTo("data: tick\n\n");
    }

    @Test
    void htmlContentTypeKeepsBuffering() throws IOException {
        wrapper.setContentType("text/html;charset=UTF-8");
        wrapper.getOutputStream().write("<html>".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.isPassthrough()).isFalse();
        assertThat(wrapper.getContentAsString()).isEqualTo("<html>");
        assertThat(originalResponse.getContentAsByteArray()).isEmpty();
    }

    /**
     * Spring's message converters declare the type as a plain header (ServletServerHttpResponse
     * writes every header with addHeader), so a JSON API response never reaches setContentType;
     * it has to be recognised there or it is buffered up to the cap for nothing.
     */
    @Test
    void nonHtmlContentTypeAddedAsAHeaderSwitchesToPassthrough() throws IOException {
        wrapper.addHeader("Content-Type", "application/json");
        wrapper.getOutputStream().write("{\"id\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.isPassthrough()).isTrue();
        assertThat(originalResponse.getContentAsString()).isEqualTo("{\"id\":1}");
    }

    @Test
    void nonHtmlContentTypeSetAsAHeaderSwitchesToPassthrough() throws IOException {
        wrapper.setHeader("content-type", "application/octet-stream");

        assertThat(wrapper.isPassthrough()).isTrue();
    }

    @Test
    void htmlContentTypeSetAsAHeaderKeepsBuffering() throws IOException {
        wrapper.addHeader("Content-Type", "text/html;charset=UTF-8");
        wrapper.getOutputStream().write("<html>".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.isPassthrough()).isFalse();
        assertThat(originalResponse.getContentAsByteArray()).isEmpty();
    }

    @Test
    void enablePassthroughFlushesBufferAndRoutesLaterWrites() throws IOException {
        wrapper.getOutputStream().write("early".getBytes(StandardCharsets.UTF_8));
        wrapper.enablePassthrough();
        wrapper.getOutputStream().write("-late".getBytes(StandardCharsets.UTF_8));

        assertThat(originalResponse.getContentAsString()).isEqualTo("early-late");
        // buffered content was handed off; nothing left to copy
        wrapper.copyBodyToResponse();
        assertThat(originalResponse.getContentAsString()).isEqualTo("early-late");
    }

    @Test
    void passthroughRoutesWriterContent() throws IOException {
        PrintWriter writer = wrapper.getWriter();
        writer.write("early");
        wrapper.enablePassthrough();
        writer.write("-late");
        writer.flush();

        assertThat(originalResponse.getContentAsString()).isEqualTo("early-late");
    }

    @Test
    void resetBufferResetsTheRealResponseInPassthrough() throws IOException {
        wrapper.enablePassthrough();
        wrapper.getOutputStream().write("streamed".getBytes(StandardCharsets.UTF_8));

        wrapper.resetBuffer();

        assertThat(originalResponse.getContentAsByteArray()).isEmpty();
    }

    /** reset() clears status and headers on the real response; the buffered body has to go with them. */
    @Test
    void resetClearsTheBufferAlongWithTheRealResponse() throws IOException {
        wrapper.setHeader("X-Stale", "yes");
        wrapper.getOutputStream().write("stale".getBytes(StandardCharsets.UTF_8));

        wrapper.reset();

        assertThat(originalResponse.containsHeader("X-Stale")).isFalse();
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }

    @Test
    void isCommittedFollowsTheRealResponse() {
        originalResponse.setCommitted(true);

        assertThat(wrapper.isCommitted()).isTrue();
    }

    /** After sendError the container renders its error page; a body buffered before it must not be written over it. */
    @Test
    void sendErrorDropsTheBufferedBody() throws IOException {
        wrapper.getOutputStream().write("<html><body>half".getBytes(StandardCharsets.UTF_8));

        wrapper.sendError(500, "boom");

        assertThat(originalResponse.getStatus()).isEqualTo(500);
        assertThat(originalResponse.getErrorMessage()).isEqualTo("boom");
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }

    @Test
    void sendRedirectDropsTheBufferedBody() throws IOException {
        wrapper.getOutputStream().write("<html><body>half".getBytes(StandardCharsets.UTF_8));

        wrapper.sendRedirect("/elsewhere");

        assertThat(originalResponse.getRedirectedUrl()).isEqualTo("/elsewhere");
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }

    /** An HTML body past the cap streams through rather than being held in heap; it gets no toolbar. */
    @Test
    void htmlBodyBeyondTheCapSwitchesToPassthrough() throws IOException {
        wrapper.setContentType("text/html");
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte) 'x');
        int chunks = ContentBufferingResponseWrapper.MAX_BUFFERED_BYTES / chunk.length + 1;

        ServletOutputStream out = wrapper.getOutputStream();
        for (int i = 0; i < chunks; i++) {
            out.write(chunk);
        }
        out.write('!');

        assertThat(wrapper.isPassthrough()).isTrue();
        assertThat(originalResponse.getContentAsByteArray()).hasSize(chunks * chunk.length + 1);
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }
}
