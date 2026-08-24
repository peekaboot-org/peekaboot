package org.peekaboot.backend.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the response body so the dev toolbar can be injected into HTML
 * pages. Only text/html responses stay buffered: as soon as a non-HTML
 * content type is set (JSON APIs, downloads, text/event-stream) - or
 * {@link #enablePassthrough()} is called explicitly (async requests) - the
 * wrapper hands the buffered bytes to the real response and delegates all
 * further writes directly, so streaming and async responses work and large
 * non-HTML bodies are not held in heap.
 */
public class ContentBufferingResponseWrapper extends HttpServletResponseWrapper {

    private static final String CONTENT_TYPE_HTML = "text/html";

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private volatile boolean committed = false;
    private volatile boolean passthrough = false;

    public ContentBufferingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void setContentType(String type) {
        super.setContentType(type);
        if (type != null && !type.contains(CONTENT_TYPE_HTML)) {
            try {
                enablePassthrough();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to switch response to passthrough mode", e);
            }
        }
    }

    /**
     * Stops buffering: flushes anything buffered so far to the real response
     * and routes all subsequent writes directly to it.
     */
    public void enablePassthrough() throws IOException {
        if (passthrough) {
            return;
        }
        if (writer != null) {
            writer.flush();
        }
        passthrough = true;
        if (buffer.size() > 0) {
            buffer.writeTo(getResponse().getOutputStream());
            buffer.reset();
        }
    }

    public boolean isPassthrough() {
        return passthrough;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (outputStream == null) {
            outputStream = new SwitchableServletOutputStream();
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStream != null) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        if (writer == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            writer = new PrintWriter(new SwitchableWriter(new OutputStreamWriter(new SwitchableServletOutputStream(), charset)));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (outputStream != null) {
            outputStream.flush();
        }
        if (passthrough) {
            getResponse().flushBuffer();
        }
    }

    @Override
    public void resetBuffer() {
        buffer.reset();
    }

    @Override
    public boolean isCommitted() {
        return committed || (passthrough && getResponse().isCommitted());
    }

    public byte[] getContentAsByteArray() {
        if (writer != null) {
            writer.flush();
        }
        return buffer.toByteArray();
    }

    public String getContentAsString() {
        if (writer != null) {
            writer.flush();
        }
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return buffer.toString(charset);
    }

    public void copyBodyToResponse() throws IOException {
        if (passthrough) {
            return;
        }
        if (buffer.size() > 0) {
            HttpServletResponse response = (HttpServletResponse) getResponse();
            response.setContentLength(buffer.size());
            buffer.writeTo(response.getOutputStream());
            committed = true;
        }
    }

    public void copyBodyToResponse(byte[] content) throws IOException {
        HttpServletResponse response = (HttpServletResponse) getResponse();
        response.setContentLength(content.length);
        response.getOutputStream().write(content);
        committed = true;
    }

    /**
     * Writes into the buffer until passthrough is enabled, then directly into
     * the real response's output stream.
     */
    private class SwitchableServletOutputStream extends ServletOutputStream {

        @Override
        public void write(int b) throws IOException {
            if (passthrough) {
                getResponse().getOutputStream().write(b);
            } else {
                buffer.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (passthrough) {
                getResponse().getOutputStream().write(b, off, len);
            } else {
                buffer.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            if (passthrough) {
                getResponse().getOutputStream().flush();
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            // buffering wrapper does not support non-blocking IO
        }
    }

    /**
     * In passthrough mode nobody flushes the wrapping encoder at end of
     * request (processResponse is skipped), so flush through on every write
     * to guarantee delivery; while buffering, writes stay cheap.
     */
    private class SwitchableWriter extends java.io.Writer {

        private final OutputStreamWriter delegate;

        private SwitchableWriter(OutputStreamWriter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            delegate.write(cbuf, off, len);
            if (passthrough) {
                delegate.flush();
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
