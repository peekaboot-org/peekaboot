package org.peekaboot.backend.testsupport;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A response whose writes fail the way a container's do. The first {@code failingWrites}
 * writes throw {@code failure}; with {@code commitsOnFailure} the response is committed
 * by the bytes that were already on the wire. Later writes succeed and land in the body.
 */
public final class FailingWriteResponse extends MockHttpServletResponse {

    private final IOException failure;
    private final int failingWrites;
    private final boolean commitsOnFailure;
    private int writeAttempts;

    /** A response whose first write fails, then recovers. */
    public FailingWriteResponse(IOException failure, boolean commitsOnFailure) {
        this(failure, 1, commitsOnFailure);
    }

    private FailingWriteResponse(IOException failure, int failingWrites, boolean commitsOnFailure) {
        this.failure = failure;
        this.failingWrites = failingWrites;
        this.commitsOnFailure = commitsOnFailure;
    }

    /** A response whose socket is gone: every write fails with {@code failure}. */
    public static FailingWriteResponse failingEveryWrite(IOException failure) {
        return new FailingWriteResponse(failure, Integer.MAX_VALUE, false);
    }

    /** How many writes were attempted, the failed ones included. */
    public int writeAttempts() {
        return writeAttempts;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        ServletOutputStream real = super.getOutputStream();
        return new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                attempt();
                real.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                attempt();
                real.write(b, off, len);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {}
        };
    }

    private void attempt() throws IOException {
        if (++writeAttempts <= failingWrites) {
            setCommitted(commitsOnFailure);
            throw failure;
        }
    }
}
