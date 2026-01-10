package net.osslabz.peekaboot.backend.domain.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorageInfoTest {

    @Test
    void of_shouldCalculateUsedPercent() {
        StorageInfo info = StorageInfo.of("/", 500_000_000_000L, 200_000_000_000L);

        assertThat(info.path()).isEqualTo("/");
        assertThat(info.total()).isEqualTo(500_000_000_000L);
        assertThat(info.free()).isEqualTo(200_000_000_000L);
        assertThat(info.usedPercent()).isEqualTo(60.0);
    }

    @Test
    void of_shouldHandleZeroTotal() {
        StorageInfo info = StorageInfo.of("/", 0L, 0L);
        assertThat(info.usedPercent()).isEqualTo(0.0);
    }

    @Test
    void of_shouldRoundToTwoDecimals() {
        StorageInfo info = StorageInfo.of("/", 1_000_000_000L, 666_666_667L);
        assertThat(info.usedPercent()).isEqualTo(33.33);
    }
}
