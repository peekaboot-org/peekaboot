package org.peekaboot.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

class IntentionalLogFilterTest {

    private final LoggerContext context = new LoggerContext();

    private IntentionalLogFilter filter(String logger, String message) {
        IntentionalLogFilter filter = new IntentionalLogFilter();
        filter.setLogger(logger);
        filter.setMessage(message);
        return started(filter);
    }

    private FilterReply decide(IntentionalLogFilter filter, String logger, String message) {
        Logger source = context.getLogger(logger);
        return filter.decide(new LoggingEvent(Logger.FQCN, source, Level.ERROR, message, null, null));
    }

    private IntentionalLogFilter started(IntentionalLogFilter filter) {
        filter.setContext(context);
        filter.start();
        return filter;
    }

    @Test
    void deniesAnEventWhenLoggerAndMessageBothMatch() {
        IntentionalLogFilter filter = filter("a.Emitter", "order PK-.* is still PLACED");

        assertThat(decide(filter, "a.Emitter", "order PK-42 is still PLACED")).isEqualTo(FilterReply.DENY);
    }

    @Test
    void staysNeutralWhenOnlyTheLoggerMatches() {
        IntentionalLogFilter filter = filter("a.Emitter", "expected failure");

        assertThat(decide(filter, "a.Emitter", "a different failure")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void staysNeutralWhenOnlyTheMessageMatches() {
        IntentionalLogFilter filter = filter("a.Emitter", "expected failure");

        assertThat(decide(filter, "b.Other", "expected failure")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void matchesTheWholeFormattedMessage() {
        IntentionalLogFilter filter = filter("a.Emitter", "expected failure");

        assertThat(decide(filter, "a.Emitter", "expected failure and more")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void aDotAllPatternMatchesAMultiLineBanner() {
        IntentionalLogFilter filter = filter("a.Emitter", "(?s)\\s*=+\\s*:: Banner ::.*");

        assertThat(decide(filter, "a.Emitter", "\n=====\n :: Banner :: \n=====\n body"))
                .isEqualTo(FilterReply.DENY);
        assertThat(decide(filter, "a.Emitter", "\n=====\n :: Other ::\n=====\n"))
                .isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void staysNeutralForAnEventWithoutAMessage() {
        IntentionalLogFilter filter = filter("a.Emitter", ".*");

        assertThat(decide(filter, "a.Emitter", null)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void doesNotStartWithoutALogger() {
        IntentionalLogFilter filter = new IntentionalLogFilter();
        filter.setMessage("expected failure");

        assertThat(started(filter).isStarted()).isFalse();
    }

    @Test
    void doesNotStartWithoutAMessage() {
        IntentionalLogFilter filter = new IntentionalLogFilter();
        filter.setLogger("a.Emitter");

        assertThat(started(filter).isStarted()).isFalse();
    }

    @Test
    void startsWhenLoggerAndMessageAreSet() {
        assertThat(filter("a.Emitter", "expected failure").isStarted()).isTrue();
    }

    @Test
    void staysNeutralUntilStarted() {
        IntentionalLogFilter filter = new IntentionalLogFilter();
        filter.setLogger("a.Emitter");
        filter.setMessage("expected failure");

        assertThat(decide(filter, "a.Emitter", "expected failure")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void staysNeutralWhenStartedWithoutAMessage() {
        IntentionalLogFilter filter = new IntentionalLogFilter();
        filter.setLogger("a.Emitter");

        assertThat(decide(started(filter), "a.Emitter", "expected failure")).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test
    void staysNeutralWhenStartedWithoutALogger() {
        IntentionalLogFilter filter = new IntentionalLogFilter();
        filter.setMessage("expected failure");

        assertThat(decide(started(filter), "a.Emitter", "expected failure")).isEqualTo(FilterReply.NEUTRAL);
    }
}
