package org.peekaboot.backend.lifecycle;

import java.util.List;

/**
 * The metadata of every {@code DataSource} bean, as one bean of its own type.
 *
 * <p>A {@code List<DataSourceMetadata>} bean would not do: Spring resolves that type by
 * collecting the {@code DataSourceMetadata} beans first and only falls back to a list bean
 * when there are none, so a single application bean of that type would silently replace
 * the whole list with itself.
 */
public record DataSourceMetadataList(List<DataSourceMetadata> entries) {

    public static final DataSourceMetadataList EMPTY = new DataSourceMetadataList(List.of());

    public DataSourceMetadataList {
        entries = List.copyOf(entries);
    }
}
