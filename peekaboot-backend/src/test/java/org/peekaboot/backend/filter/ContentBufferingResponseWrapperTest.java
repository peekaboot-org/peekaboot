package org.peekaboot.backend.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    void flushBufferFlushesThePendingWriter() throws IOException {
        wrapper.getWriter().write("Test Content");
        wrapper.flushBuffer();

        assertThat(wrapper.getContentAsString()).isEqualTo("Test Content");
    }

    /**
     * The container never sees this wrapper's getWriter(), so it never locks the character
     * encoding; a content type declared after the writer wrote changes what the response
     * says while the buffered bytes stay encoded as they were.
     */
    @Test
    void contentIsDecodedWithTheCharsetTheWriterEncodedWith() throws IOException {
        wrapper.setContentType("text/html;charset=UTF-8");
        wrapper.getWriter().write("Grüße");
        wrapper.setContentType("text/html;charset=ISO-8859-1");

        assertThat(wrapper.getContentAsString()).isEqualTo("Grüße");
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
        assertThat(wrapper.isCommitted()).isFalse();

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

    /** Servlet 6.1 added overloads to both families; each one replaces the body the same way. */
    @ParameterizedTest
    @MethodSource("bodyReplacingOverloads")
    void everySendErrorAndSendRedirectOverloadDropsTheBufferedBody(
            ThrowingConsumer<ContentBufferingResponseWrapper> send) throws Throwable {
        wrapper.getOutputStream().write("<html><body>half".getBytes(StandardCharsets.UTF_8));

        send.accept(wrapper);

        assertThat(originalResponse.isCommitted()).isTrue();
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }

    static Stream<Arguments> bodyReplacingOverloads() {
        return Stream.of(
                overload("sendError(int)", w -> w.sendError(500)),
                overload("sendRedirect(String, int)", w -> w.sendRedirect("/elsewhere", 301)),
                overload("sendRedirect(String, boolean)", w -> w.sendRedirect("/elsewhere", false)),
                overload("sendRedirect(String, int, boolean)", w -> w.sendRedirect("/elsewhere", 301, false)));
    }

    private static Arguments overload(String name, ThrowingConsumer<ContentBufferingResponseWrapper> send) {
        return Arguments.of(Named.of(name, send));
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

    /**
     * The writer's encoder is mid-flush when its bytes tip the buffer over the cap, so the
     * hand-over happens from inside a write. Nothing may be dropped or repeated on the way.
     */
    @Test
    void writerContentBeyondTheCapSwitchesToPassthroughIntact() throws IOException {
        wrapper.setContentType("text/html");
        char[] chunk = new char[64 * 1024];
        Arrays.fill(chunk, 'x');
        int chunks = ContentBufferingResponseWrapper.MAX_BUFFERED_BYTES / chunk.length + 1;
        StringBuilder expected = new StringBuilder();

        PrintWriter writer = wrapper.getWriter();
        for (int i = 0; i < chunks; i++) {
            writer.write(chunk);
            expected.append(chunk);
        }
        writer.write('!');
        expected.append('!');
        writer.flush();

        assertThat(wrapper.isPassthrough()).isTrue();
        assertThat(originalResponse.getContentAsString()).isEqualTo(expected.toString());
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }

    /**
     * An async handler's worker can write while the request thread is still handing the
     * buffered bytes over. The real response below stalls the hand-over inside its write,
     * starts the worker there, and resumes once the worker has either finished (it overtook
     * the hand-over) or is blocked waiting for it, so the outcome is deterministic either way.
     */
    @Test
    void aWriteDuringTheHandOverLandsAfterTheBufferedBytes() throws Exception {
        CountDownLatch handOverStarted = new CountDownLatch(1);
        Worker[] worker = new Worker[1];
        originalResponse = new MockHttpServletResponse() {
            private final ServletOutputStream stalling =
                    new StallingServletOutputStream(super.getOutputStream(), () -> {
                        handOverStarted.countDown();
                        awaitBlockedOrDone(worker[0]);
                    });

            @Override
            public ServletOutputStream getOutputStream() {
                return stalling;
            }
        };
        wrapper = new ContentBufferingResponseWrapper(originalResponse);
        ServletOutputStream out = wrapper.getOutputStream();
        out.write("early".getBytes(StandardCharsets.UTF_8));
        worker[0] = new Worker(() -> {
            handOverStarted.await();
            out.write("-late".getBytes(StandardCharsets.UTF_8));
        });

        wrapper.enablePassthrough();
        worker[0].join();

        assertThat(originalResponse.getContentAsString()).isEqualTo("early-late");
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }

    /**
     * The writer variant: chars a worker writes after the request thread drained the writer
     * but before the hand-over sit in the encoder, unflushed because passthrough was still
     * off, and in passthrough nothing drains the writer at end of request. The wrapper's
     * package-private seam runs the worker in exactly that window.
     */
    @Test
    void aWriterWriteBetweenTheDrainAndTheHandOverStillReachesTheResponse() throws Exception {
        CountDownLatch writerDrained = new CountDownLatch(1);
        Worker[] worker = new Worker[1];
        wrapper = new ContentBufferingResponseWrapper(originalResponse, () -> {
            writerDrained.countDown();
            awaitBlockedOrDone(worker[0]);
        });
        PrintWriter writer = wrapper.getWriter();
        writer.write("early");
        worker[0] = new Worker(() -> {
            writerDrained.await();
            writer.write("-late");
        });

        wrapper.enablePassthrough();
        worker[0].join();

        assertThat(originalResponse.getContentAsString()).isEqualTo("early-late");
        assertThat(wrapper.getContentAsByteArray()).isEmpty();
    }

    /** Resumes once the worker has finished, or is blocked on a monitor the calling thread holds. */
    private static void awaitBlockedOrDone(Worker worker) {
        await().pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(1))
                .until(() -> worker.isDone() || worker.isBlocked());
    }

    /**
     * A second thread writing through the wrapper. Starts at once; a failure in the body is
     * kept for {@link #join()} to report instead of reaching stderr as an uncaught exception.
     */
    private static final class Worker {

        private final Thread thread;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Worker(ThrowingCallable body) {
            thread = new Thread(
                    () -> {
                        try {
                            body.call();
                        } catch (Throwable t) {
                            failure.set(t);
                        } finally {
                            done.countDown();
                        }
                    },
                    "async-writer");
            thread.start();
        }

        boolean isDone() {
            return done.getCount() == 0;
        }

        boolean isBlocked() {
            return thread.getState() == Thread.State.BLOCKED;
        }

        void join() throws InterruptedException {
            assertThat(thread.join(Duration.ofSeconds(5))).as("worker finished").isTrue();
            assertThat(failure.get()).as("worker failure").isNull();
        }
    }

    /** Runs {@code beforeHandOver} once, at the first write from the thread that created it, the request thread. */
    private static final class StallingServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final Runnable beforeHandOver;
        private final Thread requestThread = Thread.currentThread();
        private boolean handedOver;

        StallingServletOutputStream(ServletOutputStream delegate, Runnable beforeHandOver) {
            this.delegate = delegate;
            this.beforeHandOver = beforeHandOver;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (Thread.currentThread().equals(requestThread) && !handedOver) {
                handedOver = true;
                beforeHandOver.run();
            }
            delegate.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            // not needed by the wrapper under test
        }
    }
}
