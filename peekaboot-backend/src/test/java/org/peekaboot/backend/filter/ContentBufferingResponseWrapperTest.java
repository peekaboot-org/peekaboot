package org.peekaboot.backend.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContentBufferingResponseWrapperTest {

    @Mock
    HttpServletResponse originalResponse;

    ContentBufferingResponseWrapper wrapper;

    @BeforeEach
    void setUp() {
        wrapper = new ContentBufferingResponseWrapper(originalResponse);
    }

    @Test
    void shouldBufferOutputStreamContent() throws IOException {
        ServletOutputStream outputStream = wrapper.getOutputStream();
        outputStream.write("Hello World".getBytes(StandardCharsets.UTF_8));

        byte[] content = wrapper.getContentAsByteArray();
        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("Hello World");
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
        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(originalResponse.getOutputStream()).thenReturn(servletOutputStream);

        wrapper.getWriter().write("Buffered Content");
        wrapper.flushBuffer();
        wrapper.copyBodyToResponse();

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("Buffered Content");
        assertThat(wrapper.isCommitted()).isTrue();
    }

    @Test
    void shouldCopyModifiedContentToResponse() throws IOException {
        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        TestServletOutputStream servletOutputStream = new TestServletOutputStream(originalOutput);
        when(originalResponse.getOutputStream()).thenReturn(servletOutputStream);

        byte[] modifiedContent = "Modified Content".getBytes(StandardCharsets.UTF_8);
        wrapper.copyBodyToResponse(modifiedContent);

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("Modified Content");
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
        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        when(originalResponse.getOutputStream()).thenReturn(new TestServletOutputStream(originalOutput));

        wrapper.setContentType("application/json");
        wrapper.getOutputStream().write("{\"id\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.isPassthrough()).isTrue();
        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("{\"id\":1}");
    }

    @Test
    void eventStreamContentTypeSwitchesToPassthrough() throws IOException {
        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        when(originalResponse.getOutputStream()).thenReturn(new TestServletOutputStream(originalOutput));

        wrapper.setContentType("text/event-stream");
        wrapper.getOutputStream().write("data: tick\n\n".getBytes(StandardCharsets.UTF_8));
        wrapper.flushBuffer();

        assertThat(wrapper.isPassthrough()).isTrue();
        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("data: tick\n\n");
    }

    @Test
    void htmlContentTypeKeepsBuffering() throws IOException {
        wrapper.setContentType("text/html;charset=UTF-8");
        wrapper.getOutputStream().write("<html>".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.isPassthrough()).isFalse();
        assertThat(wrapper.getContentAsString()).isEqualTo("<html>");
    }

    @Test
    void enablePassthroughFlushesBufferAndRoutesLaterWrites() throws IOException {
        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        when(originalResponse.getOutputStream()).thenReturn(new TestServletOutputStream(originalOutput));

        wrapper.getOutputStream().write("early".getBytes(StandardCharsets.UTF_8));
        wrapper.enablePassthrough();
        wrapper.getOutputStream().write("-late".getBytes(StandardCharsets.UTF_8));

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("early-late");
        // buffered content was handed off; nothing left to copy
        wrapper.copyBodyToResponse();
        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("early-late");
    }

    @Test
    void passthroughRoutesWriterContent() throws IOException {
        ByteArrayOutputStream originalOutput = new ByteArrayOutputStream();
        when(originalResponse.getOutputStream()).thenReturn(new TestServletOutputStream(originalOutput));

        PrintWriter writer = wrapper.getWriter();
        writer.write("early");
        wrapper.enablePassthrough();
        writer.write("-late");
        writer.flush();

        assertThat(originalOutput.toString(StandardCharsets.UTF_8)).isEqualTo("early-late");
    }

    private static class TestServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream output;

        TestServletOutputStream(ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int b) throws IOException {
            output.write(b);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(jakarta.servlet.WriteListener listener) {}
    }
}
