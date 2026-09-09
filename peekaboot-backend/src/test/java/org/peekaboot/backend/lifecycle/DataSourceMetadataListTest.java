package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataSourceMetadataListTest {

    /** A bean shared by the banner and the dashboard must not change under either of them. */
    @Test
    void entriesAreACopyTheCallerCannotAlter() {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        List<DataSourceMetadata> source = new ArrayList<>(List.of(metadata));

        DataSourceMetadataList list = new DataSourceMetadataList(source);
        source.clear();

        assertThat(list.entries()).containsExactly(metadata);
        assertThatThrownBy(() -> list.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
