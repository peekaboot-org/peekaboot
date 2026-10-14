package org.peekaboot.autoconfigure;

import java.util.List;
import java.util.Map;

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

    /** The tracing-feature switch; on unless set to false. */
    static final String TRACING_ENABLED = "peekaboot.tracing.enabled";

    /** The async-instrumentation switch; on unless set to false. */
    static final String TRACING_ASYNC = "peekaboot.tracing.async";

    /** The event-listener-instrumentation switch; on unless set to false. */
    static final String TRACING_EVENT_LISTENERS = "peekaboot.tracing.event-listeners";

    /** The insights-feature switch; on unless set to false. */
    static final String INSIGHTS_ENABLED = "peekaboot.insights.enabled";

    /** The automatic-security switch; defaulted from the launch context like {@link #ENABLED}. */
    static final String SECURITY_ENABLED = "peekaboot.security.enabled";

    /**
     * Detected-only: whether the launch context was a deployment, independent of any explicit
     * {@link #SECURITY_ENABLED} override. An application must never set this itself - it exists
     * so a component can tell a detected default from an override after {@link #SECURITY_ENABLED}
     * has already resolved, which reading that key alone cannot answer.
     */
    static final String SECURITY_DEPLOYMENT_DETECTED = "peekaboot.security.deployment-detected";

    /** The error-page switch; defaulted from the launch context like {@link #ENABLED}. */
    static final String ERROR_PAGE_ENABLED = "peekaboot.error-page.enabled";

    /** Makes the error page outrank the application's own, where the page is already enabled. */
    static final String ERROR_PAGE_OVERRIDE = "peekaboot.error-page.override";

    /**
     * Every key an auto-configuration is gated on with {@code @ConditionalOnBooleanProperty}.
     * Those conditions match only {@code true}, while the binder also reads {@code yes},
     * {@code on} and {@code 1} as true, so {@link BooleanSwitches} refuses those values.
     */
    static final List<String> BOOLEAN_SWITCHES = List.of(
            ENABLED,
            DEV_TOOLBAR,
            LIFECYCLE_ENABLED,
            TRACING_ENABLED,
            TRACING_ASYNC,
            TRACING_EVENT_LISTENERS,
            INSIGHTS_ENABLED,
            SECURITY_ENABLED,
            ERROR_PAGE_ENABLED,
            ERROR_PAGE_OVERRIDE);

    /**
     * The switches some condition also gates on with {@code havingValue = false}, which matches
     * only {@code false}, so {@link BooleanSwitches} refuses {@code no}, {@code off} and
     * {@code 0} on these too.
     */
    static final List<String> SWITCHES_ALSO_GATED_ON_FALSE = List.of(ERROR_PAGE_OVERRIDE);

    /**
     * What a switch the detection does not default reads as when nothing sets it: the value its
     * {@code matchIfMissing} condition and its bound property both take. A detected switch falls
     * back to its detected value instead, which is always in the environment.
     */
    static final Map<String, String> UNSET_SWITCH_DEFAULTS = Map.of(
            LIFECYCLE_ENABLED, "true",
            TRACING_ENABLED, "true",
            TRACING_ASYNC, "true",
            TRACING_EVENT_LISTENERS, "true",
            INSIGHTS_ENABLED, "true",
            ERROR_PAGE_OVERRIDE, "false");

    /**
     * The property source carrying the launch-context defaults - present under this name only
     * when the application has not already called {@code SpringApplication.setDefaultProperties}
     * or {@code SpringApplicationBuilder.properties}; otherwise these entries are folded into
     * Boot's own {@code defaultProperties} source instead and this name never appears. A signal
     * that must survive regardless of which branch ran needs its own property key, not this
     * source's name - see {@link #SECURITY_DEPLOYMENT_DETECTED}.
     */
    static final String DETECTION_PROPERTY_SOURCE_NAME = "peekabootDetection";

    private PeekabootPropertyKeys() {}
}
