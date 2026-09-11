package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.osslabz.jdbc.DatabaseProduct;
import org.junit.jupiter.api.Test;

class DataSourceMetadataListTest {

    /** A bean shared by the banner and the dashboard must not change under either of them. */
    @Test
    void entriesAreACopyTheCallerCannotAlter() {
        DataSourceMetadata metadata = new DataSourceMetadata(
                "primary", "sa", List.of(), "app", DatabaseProduct.H2, Map.of(), "H2", "2", "H2 JDBC Driver");
        List<DataSourceMetadata> source = new ArrayList<>(List.of(metadata));

        DataSourceMetadataList list = new DataSourceMetadataList(source);
        source.clear();

        assertThat(list.entries()).containsExactly(metadata);
        assertThatThrownBy(() -> list.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
