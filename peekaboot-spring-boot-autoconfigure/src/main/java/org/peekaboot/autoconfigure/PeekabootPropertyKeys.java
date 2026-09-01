package org.peekaboot.autoconfigure;

/** Property keys read outside a {@code @ConfigurationProperties} binding. */
final class PeekabootPropertyKeys {

    /** The master switch; has no fallback of its own, see PeekabootDefaultsEnvironmentPostProcessor. */
    static final String ENABLED = "peekaboot.enabled";

    private PeekabootPropertyKeys() {}
}
