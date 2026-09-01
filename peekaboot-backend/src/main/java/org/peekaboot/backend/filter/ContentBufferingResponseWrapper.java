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
 * pages. Only text/html responses stay buffered, and only up to
 * {@link #MAX_BUFFERED_BYTES}: as soon as a non-HTML content type is set (JSON
 * APIs, downloads, text/event-stream), the buffer outgrows the cap, or
 * {@link #enablePassthrough()} is called explicitly (async requests), the
 * wrapper hands the buffered bytes to the real response and delegates all
 * further writes directly, so streaming and async responses work and no large
 * body is held in heap. A page past the cap is served without the toolbar.
 *
 * <p>Reset and commit semantics follow Spring's {@code ContentCachingResponseWrapper}:
 * {@link #reset()} clears the buffer along with the real response, {@link #isCommitted()}
 * is true once the real response is, and {@code sendError}/{@code sendRedirect} drop
 * what was buffered, since the container replaces the body either way.
 */
public class ContentBufferingResponseWrapper extends HttpServletResponseWrapper {

    private static final String CONTENT_TYPE_HTML = "text/html";

    /** Past this an HTML body streams through uninjected rather than being held in heap. */
    static final int MAX_BUFFERED_BYTES = 2 * 1024 * 1024;

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
        switchToPassthrough();
    }

    /**
     * The hand-over itself, without flushing the writer: called from inside a write when the
     * buffer outgrows the cap, where the writer's encoder is mid-flush already.
     */
    private void switchToPassthrough() throws IOException {
        passthrough = true;
        if (buffer.size() > 0) {
            buffer.writeTo(getResponse().getOutputStream());
            buffer.reset();
        }
    }

    private void bufferOrPassThrough(byte[] b, int off, int len) throws IOException {
        if (passthrough) {
            getResponse().getOutputStream().write(b, off, len);
            return;
        }
        buffer.write(b, off, len);
        if (buffer.size() > MAX_BUFFERED_BYTES) {
            switchToPassthrough();
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
            writer = new PrintWriter(
                    new SwitchableWriter(new OutputStreamWriter(new SwitchableServletOutputStream(), charset)));
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
        if (passthrough) {
            getResponse().resetBuffer();
        } else {
            buffer.reset();
        }
    }

    @Override
    public void reset() {
        super.reset();
        buffer.reset();
    }

    @Override
    public boolean isCommitted() {
        return committed || getResponse().isCommitted();
    }

    @Override
    public void sendError(int sc) throws IOException {
        buffer.reset();
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        buffer.reset();
        super.sendError(sc, msg);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        buffer.reset();
        super.sendRedirect(location);
    }

    @Override
    public void sendRedirect(String location, int sc) throws IOException {
        buffer.reset();
        super.sendRedirect(location, sc);
    }

    @Override
    public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        buffer.reset();
        super.sendRedirect(location, clearBuffer);
    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        buffer.reset();
        super.sendRedirect(location, sc, clearBuffer);
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
    private final class SwitchableServletOutputStream extends ServletOutputStream {

        @Override
        public void write(int b) throws IOException {
            bufferOrPassThrough(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            bufferOrPassThrough(b, off, len);
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
    private final class SwitchableWriter extends java.io.Writer {

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
