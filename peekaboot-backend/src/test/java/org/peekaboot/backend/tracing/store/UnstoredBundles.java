package org.peekaboot.backend.tracing.store;

import java.util.function.Consumer;

/** Bundles no store keeps, for tests that drive a bundle directly through the store's own methods. */
public final class UnstoredBundles {

    /** The filing step of a bundle no store keeps: it files nothing. */
    public static final Consumer<TraceDataBundle> UNFILED = bundle -> {};

    private UnstoredBundles() {}
}
