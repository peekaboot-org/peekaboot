package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StorageInfoTest {

    @Test
    void ofCalculatesUsedPercent() {
        StorageInfo info = StorageInfo.of("/", 500_000_000_000L, 200_000_000_000L);

        assertThat(info.path()).isEqualTo("/");
        assertThat(info.total()).isEqualTo(500_000_000_000L);
        assertThat(info.free()).isEqualTo(200_000_000_000L);
        assertThat(info.usedPercent()).isEqualTo(60.0);
    }

    @Test
    void ofHandlesZeroTotal() {
        StorageInfo info = StorageInfo.of("/", 0L, 0L);
        assertThat(info.usedPercent()).isEqualTo(0.0);
    }

    @Test
    void ofRoundsToTwoDecimals() {
        StorageInfo info = StorageInfo.of("/", 1_000_000_000L, 666_666_667L);
        assertThat(info.usedPercent()).isEqualTo(33.33);
    }
}
