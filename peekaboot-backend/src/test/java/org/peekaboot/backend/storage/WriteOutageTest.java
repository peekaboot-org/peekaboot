package org.peekaboot.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WriteOutageTest {

    private final WriteOutage outage = new WriteOutage();
    private final AtomicInteger warnings = new AtomicInteger();

    @Test
    void theFirstFailureOfAnOutageIsReportedAndTheRestAreNot() {
        outage.failed(warnings::incrementAndGet);
        outage.failed(warnings::incrementAndGet);
        outage.failed(warnings::incrementAndGet);

        assertThat(warnings).hasValue(1);
    }

    @Test
    void aSuccessEndsTheOutageSoTheNextFailureIsReportedAgain() {
        outage.failed(warnings::incrementAndGet);
        outage.succeeded();

        outage.failed(warnings::incrementAndGet);
        outage.failed(warnings::incrementAndGet);

        assertThat(warnings).hasValue(2);
    }
}
