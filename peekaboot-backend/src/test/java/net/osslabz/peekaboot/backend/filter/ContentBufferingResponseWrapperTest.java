package net.osslabz.peekaboot.backend.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
        public void setWriteListener(jakarta.servlet.WriteListener listener) {
        }
    }
}
