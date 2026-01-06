package net.osslabz.peekaboot.backend.filter;

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

public class ContentBufferingResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean committed = false;

    public ContentBufferingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (outputStream == null) {
            outputStream = new BufferingServletOutputStream(buffer);
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
            writer = new PrintWriter(new OutputStreamWriter(buffer, charset));
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
    }

    @Override
    public void resetBuffer() {
        buffer.reset();
    }

    @Override
    public boolean isCommitted() {
        return committed;
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

    private static class BufferingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream buffer;

        public BufferingServletOutputStream(ByteArrayOutputStream buffer) {
            this.buffer = buffer;
        }

        @Override
        public void write(int b) throws IOException {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            buffer.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            // Not used for buffering
        }
    }
}
