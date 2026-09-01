package org.peekaboot.autoconfigure;

/** Property keys read outside a {@code @ConfigurationProperties} binding. */
final class PeekabootPropertyKeys {

    /** The master switch; has no fallback of its own, see PeekabootDefaultsEnvironmentPostProcessor. */
    static final String ENABLED = "peekaboot.enabled";

    /** The toolbar switch; defaulted from the launch context like {@link #ENABLED}. */
    static final String DEV_TOOLBAR = "peekaboot.dev-toolbar";

    /** The persistence switch; defaulted from the launch context like {@link #ENABLED}. */
    static final String STORAGE_ENABLED = "peekaboot.storage.enabled";

    /** The lifecycle-feature switch; on unless set to false. */
    static final String LIFECYCLE_ENABLED = "peekaboot.lifecycle.enabled";

    private PeekabootPropertyKeys() {}
}
